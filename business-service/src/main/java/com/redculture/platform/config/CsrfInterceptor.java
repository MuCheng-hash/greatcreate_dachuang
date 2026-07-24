package com.redculture.platform.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class CsrfInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private final AuthProperties properties;

    public CsrfInterceptor(AuthProperties properties) {
        this.properties = properties;
    }

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
                || path.equals("/api/auth/school-register");
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
