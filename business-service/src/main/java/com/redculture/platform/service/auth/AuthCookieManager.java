package com.redculture.platform.service.auth;

import com.redculture.platform.config.AuthProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AuthCookieManager {

    private final AuthProperties properties;

    public AuthCookieManager(AuthProperties properties) {
        this.properties = properties;
    }

    public String read(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    public void write(HttpServletResponse response, AuthTokenService.IssuedTokens tokens) {
        add(response, properties.getAccessCookieName(), tokens.accessToken(), "/", properties.getAccessTokenTtlSeconds(), true);
        add(response, properties.getRefreshCookieName(), tokens.refreshToken(), "/api/auth", properties.getRefreshTokenTtlSeconds(), true);
        add(response, properties.getCsrfCookieName(), tokens.csrfToken(), "/", properties.getRefreshTokenTtlSeconds(), false);
    }

    public void clear(HttpServletResponse response) {
        add(response, properties.getAccessCookieName(), "", "/", 0, true);
        add(response, properties.getRefreshCookieName(), "", "/api/auth", 0, true);
        add(response, properties.getCsrfCookieName(), "", "/", 0, false);
    }

    public String refreshCookieName() {
        return properties.getRefreshCookieName();
    }

    public String accessCookieName() {
        return properties.getAccessCookieName();
    }

    private void add(HttpServletResponse response,
                     String name,
                     String value,
                     String path,
                     long maxAgeSeconds,
                     boolean httpOnly) {
        ResponseCookie cookie = ResponseCookie.from(name, value == null ? "" : value)
                .httpOnly(httpOnly)
                .secure(properties.isCookieSecure())
                .sameSite(properties.getCookieSameSite())
                .path(path)
                .maxAge(Duration.ofSeconds(Math.max(0L, maxAgeSeconds)))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
