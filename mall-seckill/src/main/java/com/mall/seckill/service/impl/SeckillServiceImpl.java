package com.mall.seckill.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.mall.common.constant.SeckillConstant;
import com.mall.common.to.mq.SeckillOrderTo;
import com.mall.common.utils.R;
import com.mall.common.vo.MemberRespVo;
import com.mall.seckill.feign.CouponFeignService;
import com.mall.seckill.feign.ProductFeignService;
import com.mall.seckill.interceptor.LoginUserInterceptor;
import com.mall.seckill.service.SeckillService;
import com.mall.seckill.vo.SeckillSessionsWithSkusVo;
import com.mall.seckill.vo.SeckillSkuInfoVo;
import com.mall.seckill.vo.SeckillSkuRedisVo;
import org.apache.commons.lang.StringUtils;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    CouponFeignService couponFeignService;

    @Autowired
    ProductFeignService productFeignService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    RedissonClient redissonClient;


    @Override
    public void uploadSeckillSkuLatestThreeDays() {
        R r = couponFeignService.getLatestThreeDaysSession();
        if (r.getCode() == 0) {
            String json = JSON.toJSONString(r.get("data"));
            JSONArray array = JSON.parseArray(json);
            if (array == null) return;
            List<SeckillSessionsWithSkusVo> list = array.toJavaList(SeckillSessionsWithSkusVo.class);
            saveSessionInfo(list);
            saveSessionSkuInfo(list);
        }
    }

    @Override
    public List<SeckillSkuRedisVo> getCurrentSeckillSkus() {
        long time = new Date().getTime();
        Set<String> keys = stringRedisTemplate.keys(SeckillConstant.SESSIONS_CACHE_PREFIX + "*");
        if (keys != null) {
            for (String key : keys) {
                String replace = key.replace(SeckillConstant.SESSIONS_CACHE_PREFIX, "");
                String[] strings = replace.split("_");
                long start = Long.parseLong(strings[0]);
                long end = Long.parseLong(strings[1]);
                if (time >= start && time <= end) {
                    List<String> list = stringRedisTemplate.opsForList().range(key, -100, 100);
                    BoundHashOperations<String, String, Object> ops
                            = stringRedisTemplate.boundHashOps(SeckillConstant.SECKILL_SKU_CACHE);
                    if (list != null) {
                        List<Object> objects = ops.multiGet(list);
                        if (objects != null) {
                            return objects.stream().map(o -> JSON.parseObject((String) o, SeckillSkuRedisVo.class)).toList();
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public SeckillSkuRedisVo getSkuSeckillInfo(Long skuId) {
        BoundHashOperations<String, String, String> ops = stringRedisTemplate.boundHashOps(SeckillConstant.SECKILL_SKU_CACHE);
        Set<String> keys = ops.keys();
        if (keys != null && !keys.isEmpty()) {
            String reg = "\\d_" + skuId;
            for (String key : keys) {
                if (Pattern.matches(reg, key)) {
                    String json = ops.get(key);
                    SeckillSkuRedisVo vo = JSON.parseObject(json, SeckillSkuRedisVo.class);
                    if (vo != null) {
                        long now = new Date().getTime();
                        long start = vo.getStartTime();
                        long end = vo.getEndTime();
                        if (!(now >= start && now <= end)) {
                            vo.setRandomCode(null);
                        }
                        return vo;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String kill(String killId, String code, Integer num) {
        BoundHashOperations<String, String, String> ops = stringRedisTemplate.boundHashOps(SeckillConstant.SECKILL_SKU_CACHE);
        String json = ops.get(killId);
        if (!StringUtils.isEmpty(json)) {
            MemberRespVo user = LoginUserInterceptor.loginUser.get();
            SeckillSkuRedisVo redisVo = JSONObject.parseObject(json, SeckillSkuRedisVo.class);
            long startTime = redisVo.getStartTime();
            long endTime = redisVo.getEndTime();
            long now = new Date().getTime();
            long ttl = endTime - now;
            if (now >= startTime && now <= endTime) {
                String randomCode = redisVo.getRandomCode();
                String id = redisVo.getPromotionSessionId() + "_" + redisVo.getSkuId();
                if (Objects.equals(randomCode, code) && Objects.equals(id, killId)) {
                    Integer limit = redisVo.getSeckillLimit();
                    if (num <= limit) {
                        String redisKey = user.getId().toString() + "_" + id;
                        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(SeckillConstant.SECKILL_OF_USER +
                                redisKey, num.toString(), ttl, TimeUnit.MILLISECONDS);
                        if (Boolean.TRUE.equals(flag)) {
                            RSemaphore semaphore
                                    = redissonClient.getSemaphore(randomCode);
                            boolean b = semaphore.tryAcquire(num);
                            if (b) {
                                String orderSn = UUID.randomUUID().toString();
                                SeckillOrderTo to = new SeckillOrderTo();
                                to.setOrderSn(orderSn);
                                to.setMemberId(user.getId());
                                to.setNum(num);
                                to.setPromotionSessionId(redisVo.getPromotionSessionId());
                                to.setSkuId(redisVo.getSkuId());
                                to.setSeckillPrice(redisVo.getSeckillPrice());
                                rabbitTemplate.convertAndSend("order-event-exchange", "order.seckill.order", to);
                                return orderSn;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void saveSessionInfo(List<SeckillSessionsWithSkusVo> list) {
        list.forEach(session -> {
            long startTime = session.getStartTime().getTime();
            long endTime = session.getEndTime().getTime();
            String key = SeckillConstant.SESSIONS_CACHE_PREFIX + startTime + "_" + endTime + "_" + session.getId();
            if (Boolean.FALSE.equals(stringRedisTemplate.hasKey(key))) {
                List<String> skuIds = session
                        .getRelationSkus().stream()
                        .map(item -> item.getPromotionSessionId() + "_" + item.getSkuId().toString()).toList();
                stringRedisTemplate.opsForList().leftPushAll(key, skuIds);
            }
        });
    }

    private void saveSessionSkuInfo(List<SeckillSessionsWithSkusVo> list) {
        list.forEach(session -> {
            BoundHashOperations<String, Object, Object> ops
                    = stringRedisTemplate.boundHashOps(SeckillConstant.SECKILL_SKU_CACHE);
            session.getRelationSkus().forEach(vo -> {
                String token = UUID.randomUUID().toString().replace("-", "");
                if (Boolean.FALSE.equals(ops.hasKey(vo.getPromotionSessionId().toString() + "_" + vo.getSkuId().toString()))) {
                    SeckillSkuRedisVo redisVo = new SeckillSkuRedisVo();
                    R r = productFeignService.getSkuInfo(vo.getSkuId());
                    if (r.getCode() == 0) {
                        String s = JSON.toJSONString(r.get("skuInfo"));
                        SeckillSkuInfoVo skuInfoVo = JSON.parseObject(s, SeckillSkuInfoVo.class);
                        redisVo.setSkuInfo(skuInfoVo);
                    }
                    BeanUtils.copyProperties(vo, redisVo);
                    redisVo.setStartTime(session.getStartTime().getTime());
                    redisVo.setEndTime(session.getEndTime().getTime());
                    redisVo.setRandomCode(token);
                    String json = JSON.toJSONString(redisVo);
                    ops.put(vo.getPromotionSessionId().toString() + "_" + vo.getSkuId().toString(), json);
                    RSemaphore semaphore = redissonClient.getSemaphore(token);
                    semaphore.trySetPermits(vo.getSeckillCount());
                }
            });
        });
    }
}
