package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.DispatcherType;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestRateLimitInterceptorTest {

    @Test
    void limitsLoginRequestsByClientAddress() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLoginRequestsPerMinute(2);
        RequestRateLimitInterceptor interceptor = new RequestRateLimitInterceptor(properties);

        assertTrue(call(interceptor, "/api/auth/login", "127.0.0.1", null).allowed());
        assertTrue(call(interceptor, "/api/auth/login", "127.0.0.1", null).allowed());
        Result rejected = call(interceptor, "/api/auth/login", "127.0.0.1", null);
        assertFalse(rejected.allowed());
        assertEquals(429, rejected.response().getStatus());
        assertEquals("2", rejected.response().getHeader("X-RateLimit-Limit"));
    }

    @Test
    void isolatesAiLimitsByAuthenticatedAccount() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setAiRequestsPerMinute(1);
        RequestRateLimitInterceptor interceptor = new RequestRateLimitInterceptor(properties);

        assertTrue(call(interceptor, "/api/ai/qa/stream", "127.0.0.1", 1L).allowed());
        assertFalse(call(interceptor, "/api/ai/qa/stream", "10.0.0.2", 1L).allowed());
        assertTrue(call(interceptor, "/api/ai/qa/stream", "127.0.0.1", 2L).allowed());
    }

    @Test
    void ignoresSpringMvcAsyncRedispatchForReactiveStreams() throws Exception {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setAiRequestsPerMinute(1);
        RequestRateLimitInterceptor interceptor = new RequestRateLimitInterceptor(properties);

        assertTrue(call(interceptor, "/api/ai/qa/stream", "127.0.0.1", 1L).allowed());
        MockHttpServletRequest asyncRequest = new MockHttpServletRequest(
                "POST", "/api/ai/qa/stream"
        );
        asyncRequest.setDispatcherType(DispatcherType.ASYNC);
        MockHttpServletResponse asyncResponse = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(asyncRequest, asyncResponse, new Object()));
        assertEquals(200, asyncResponse.getStatus());
    }

    private Result call(RequestRateLimitInterceptor interceptor, String path, String address, Long accountId)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(address);
        if (accountId != null) {
            AuthCurrentUserVO user = new AuthCurrentUserVO();
            user.setAccountId(accountId);
            request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, user);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new Result(interceptor.preHandle(request, response, new Object()), response);
    }

    private record Result(boolean allowed, MockHttpServletResponse response) {
    }
}
