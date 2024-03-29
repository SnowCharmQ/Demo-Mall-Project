package com.mall.member.web;

import com.mall.common.utils.R;
import com.mall.member.feign.OrderFeignService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.HashMap;
import java.util.Map;

@Controller
public class WebController {

    @Autowired
    OrderFeignService orderFeignService;

    @GetMapping("/memberOrder.html")
    public String memberOrderPage(@RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum, Model model) {
        Map<String, Object> map = new HashMap<>();
        map.put("page", pageNum.toString());
        R r = orderFeignService.listWithItem(map);
        model.addAttribute("orders", r);
        return "orderList";
    }
}
