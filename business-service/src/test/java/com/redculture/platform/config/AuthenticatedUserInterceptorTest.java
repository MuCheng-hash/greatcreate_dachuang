package com.redculture.platform.config;

import com.redculture.platform.service.AuthService;
import com.redculture.platform.service.auth.AuthCookieManager;
import com.redculture.platform.service.auth.JwtTokenService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthenticatedUserInterceptorTest {

    @Test
    void rejectsRequestWithoutAuthenticatedCookie() throws Exception {
        AuthCookieManager cookieManager = mock(AuthCookieManager.class);
        when(cookieManager.accessCookieName()).thenReturn("RC_ACCESS_TOKEN");
        when(cookieManager.read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("RC_ACCESS_TOKEN")))
                .thenReturn(null);
        AuthenticatedUserInterceptor interceptor = new AuthenticatedUserInterceptor(
                mock(AuthService.class), mock(JwtTokenService.class), cookieManager
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(new MockHttpServletRequest(), response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void permitsActiveAccount() throws Exception {
        AuthService authService = mock(AuthService.class);
        JwtTokenService tokenService = mock(JwtTokenService.class);
        AuthCookieManager cookieManager = mock(AuthCookieManager.class);
        when(cookieManager.accessCookieName()).thenReturn("RC_ACCESS_TOKEN");
        when(cookieManager.read(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("RC_ACCESS_TOKEN")))
                .thenReturn("access-token");
        when(tokenService.parseAccessToken("access-token"))
                .thenReturn(new JwtTokenService.AccessTokenPrincipal(9L, "jti", Long.MAX_VALUE));
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(9L);
        user.setRoleCode("school_admin");
        when(authService.currentUser(9L)).thenReturn(user);
        AuthenticatedUserInterceptor interceptor = new AuthenticatedUserInterceptor(
                authService, tokenService, cookieManager
        );
        MockHttpServletRequest request = new MockHttpServletRequest();

        boolean allowed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(allowed);
        assertEquals(user, AuthContext.currentUser(request));
    }
}
