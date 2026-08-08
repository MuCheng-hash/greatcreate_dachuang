package com.redculture.platform.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
//前端页面路由转发，使 /login、/map、/assistant 等地址都能加载 Vue 单页应用。
public class PortalRouteController {


    //访问根地址localhost:8080时，返回的是登录页面，让客户先登陆
    @GetMapping({"/", "/index.html"})
    public String root() {
        return "redirect:/login";
    }

    //统一处理 Vue 前端的多个页面地址
    /*
    地址	对应前端页面
/login	登录页
/register	学校注册页
/map	地图资源页
/teaching-plans	AI 教学方案页
/assistant	AI 助手问答页
/agent-debug	Agent 调试/观测页面
/profile	个人中心页
这里是“服务器内部转发”，不是浏览器重定向
     */
    @GetMapping({"/login", "/register", "/map", "/teaching-plans", "/assistant", "/agent-debug", "/profile"})
    public String portal() {
        return "forward:/portal/index.html";
    }
}
