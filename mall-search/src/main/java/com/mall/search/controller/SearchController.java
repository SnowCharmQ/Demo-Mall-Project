package com.mall.search.controller;

import com.mall.search.service.MallSearchService;
import com.mall.search.vo.SearchParamVo;
import com.mall.search.vo.SearchResponseVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

@Controller
public class SearchController {

    @Autowired
    MallSearchService mallSearchService;

    @GetMapping("/list.html")
    public String listPage(SearchParamVo param, Model model, HttpServletRequest request) throws IOException {
        param.setQueryString(request.getQueryString());
        SearchResponseVo response = mallSearchService.search(param);
        model.addAttribute("result", response);
        return "list";
    }
}
