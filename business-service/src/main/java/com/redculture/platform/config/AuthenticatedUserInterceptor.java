package com.redculture.platform.config;

import com.redculture.platform.service.AuthService;
import com.redculture.platform.service.auth.AuthCookieManager;
import com.redculture.platform.service.auth.AuthTokenException;
import com.redculture.platform.service.auth.JwtTokenService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AuthenticatedUserInterceptor implements HandlerInterceptor {

    private final AuthService authService;
    private final JwtTokenService jwtTokenService;
    private final AuthCookieManager cookieManager;

    public AuthenticatedUserInterceptor(AuthService authService,
                                       JwtTokenService jwtTokenService,
                                       AuthCookieManager cookieManager) {
        this.authService = authService;
        this.jwtTokenService = jwtTokenService;
        this.cookieManager = cookieManager;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        try {
            String rawToken = cookieManager.read(request, cookieManager.accessCookieName());
            if (StringUtils.hasText(rawToken)) {
                JwtTokenService.AccessTokenPrincipal principal = jwtTokenService.parseAccessToken(rawToken);
                AuthCurrentUserVO user = authService.currentUser(principal.accountId());
                if (user != null) {
                    request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, user);
                    return true;
                }
            }
        } catch (AuthTokenException ignored) {
            // Continue with a normalized 401 response.
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
        return false;
    }
}
