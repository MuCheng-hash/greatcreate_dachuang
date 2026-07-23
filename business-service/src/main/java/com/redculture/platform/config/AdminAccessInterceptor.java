package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

@Component
public class AdminAccessInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        AuthCurrentUserVO account = AuthContext.currentUser(request);
        if (account == null) {
            writeUnauthorized(response, "请先使用管理员账号登录");
            return false;
        }

        if (!"platform_admin".equals(account.getRoleCode())) {
            writeUnauthorized(response, "当前账号无后台管理权限");
            return false;
        }

        return true;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
    }
}
