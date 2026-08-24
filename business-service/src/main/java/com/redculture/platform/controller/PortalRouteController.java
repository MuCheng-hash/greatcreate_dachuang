package com.redculture.platform.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
//前端页面路由转发，使 /login、/map、/assistant 等地址都能加载 Vue 单页应用。
public class PortalRouteController {


    //访问根地址时进入公共项目总首页；首页中的登录按钮再进入 /login。
    @GetMapping({"/", "/index.html"})
    public String root() {
        return "forward:/portal/index.html";
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
    @GetMapping({
            "/login", "/register",
            "/map", "/teaching-plans", "/classes", "/tasks", "/resource-discovery", "/assistant", "/agent-debug", "/profile",
            "/student", "/student/home", "/student/learning-footprint", "/student/tasks", "/student/resource-discovery", "/student/assistant", "/student/map", "/student/profile", "/student-home", "/learning-footprint",
            "/teacher", "/teacher/map", "/teacher/teaching-plans", "/teacher/classes", "/teacher/tasks", "/teacher/resource-discovery", "/teacher/assistant", "/teacher/agent-debug", "/teacher/profile"
    })
    public String portal() {
        return "forward:/portal/index.html";
    }
}
