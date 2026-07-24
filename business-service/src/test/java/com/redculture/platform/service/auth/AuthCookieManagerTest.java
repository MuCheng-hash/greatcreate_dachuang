package com.redculture.platform.service.auth;

import com.redculture.platform.config.AuthProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthCookieManagerTest {

    @Test
    void writesHttpOnlyAccessAndRefreshCookiesAndReadableCsrfCookie() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("local-development-secret-with-at-least-32-bytes");
        AuthCookieManager manager = new AuthCookieManager(properties);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        AuthTokenService.IssuedTokens tokens = new AuthTokenService.IssuedTokens(
                user, "access-token", "refresh-token", "csrf-token"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.write(response, tokens);

        List<String> cookies = response.getHeaders("Set-Cookie");
        assertTrue(cookies.stream().anyMatch(value -> value.contains("RC_ACCESS_TOKEN=access-token")
                && value.contains("HttpOnly") && value.contains("Path=/")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("RC_REFRESH_TOKEN=refresh-token")
                && value.contains("HttpOnly") && value.contains("Path=/api/auth")));
        assertTrue(cookies.stream().anyMatch(value -> value.contains("XSRF-TOKEN=csrf-token")
                && !value.contains("HttpOnly")));
    }
}
