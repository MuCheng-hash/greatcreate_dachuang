package com.redculture.platform.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PortalRouteController {

    @GetMapping({"/", "/index.html"})
    public String root() {
        return "redirect:/login";
    }

    @GetMapping({"/login", "/register", "/map", "/teaching-plans", "/assistant", "/profile"})
    public String portal() {
        return "forward:/portal/index.html";
    }
}
