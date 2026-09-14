package com.redculture.platform.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * 对修改服务端状态的请求执行 Cookie 与请求头双重 CSRF 校验。
 */
@Component
public class CsrfInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    /**
     * 需要执行 CSRF 校验的 HTTP 方法集合。
     */
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    /**
     * 当前组件使用的配置属性。
     */
    private final AuthProperties properties;

    /**
     * 创建 CSRF 防护拦截器。
     *
     * @param properties 相关配置属性
     */
    public CsrfInterceptor(AuthProperties properties) {
        this.properties = properties;
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
        if (!request.getRequestURI().startsWith("/api/")
                || !MUTATING_METHODS.contains(request.getMethod())
                || isExempt(request.getRequestURI())) {
            return true;
        }
        String cookieToken = readCookie(request, properties.getCsrfCookieName());
        String headerToken = request.getHeader(properties.getCsrfHeaderName());
        if (cookieToken != null && headerToken != null
                && MessageDigest.isEqual(
                cookieToken.getBytes(StandardCharsets.UTF_8), headerToken.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"CSRF token is invalid\",\"data\":null}");
        return false;
    }

    private boolean isExempt(String path) {
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/refresh")
                || path.equals("/api/auth/register");
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (jakarta.servlet.http.Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
