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

/**
 * 解析认证信息并把当前用户写入请求上下文。
 */
@Component
public class AuthenticatedUserInterceptor implements HandlerInterceptor {

    /**
     * 认证服务。
     */
    private final AuthService authService;
    /**
     * JWT 令牌服务。
     */
    private final JwtTokenService jwtTokenService;
    /**
     * 认证 Cookie 管理器。
     */
    private final AuthCookieManager cookieManager;

    /**
     * 创建登录用户认证拦截器。
     *
     * @param authService 认证服务
     * @param jwtTokenService JWT 令牌服务
     * @param cookieManager 认证 Cookie 管理器
     */
    public AuthenticatedUserInterceptor(AuthService authService,
                                       JwtTokenService jwtTokenService,
                                       AuthCookieManager cookieManager) {
        this.authService = authService;
        this.jwtTokenService = jwtTokenService;
        this.cookieManager = cookieManager;
    }

    /**
     * 在控制器执行前完成当前拦截器负责的校验。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param handler 即将执行的处理器
     * @return 校验通过时返回 {@code true}；响应已被拦截时返回 {@code false}
     * @throws Exception 校验或响应写入失败时抛出
     */
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
