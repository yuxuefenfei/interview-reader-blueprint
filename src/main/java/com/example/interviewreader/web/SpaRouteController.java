package com.example.interviewreader.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaRouteController {
    @GetMapping({"/reader", "/reader/**", "/admin", "/admin/**"})
    public String forwardToApplicationShell() {
        return "forward:/index.html";
    }
}
