package com.redculture.platform.config;

import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.service.impl.AuthServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthenticatedUserInterceptor implements HandlerInterceptor {

    private final SchoolUserAccountService schoolUserAccountService;

    public AuthenticatedUserInterceptor(SchoolUserAccountService schoolUserAccountService) {
        this.schoolUserAccountService = schoolUserAccountService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        Object accountId = request.getSession().getAttribute(AuthServiceImpl.AUTH_SESSION_KEY);
        if (accountId instanceof Long id) {
            SchoolUserAccount account = schoolUserAccountService.getById(id);
            if (account != null && account.getStatus() == AccountStatus.ACTIVE) {
                return true;
            }
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
        return false;
    }
}
