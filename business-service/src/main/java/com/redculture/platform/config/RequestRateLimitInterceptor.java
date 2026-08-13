package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RequestRateLimitInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    private final RateLimitProperties properties;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RequestRateLimitInterceptor(RateLimitProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // Spring MVC 会为 Mono/Flux 结果进行 ASYNC 二次分派；限流只统计初始请求，
        // 否则同一条已提交的 SSE 会被重复计数，甚至在二次分派时尝试写 JSON 错误体。
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        int limit = path.equals("/api/auth/login")
                ? properties.getLoginRequestsPerMinute() : properties.getAiRequestsPerMinute();
        String category = path.equals("/api/auth/login") ? "login" : "ai";
        AuthCurrentUserVO user = AuthContext.currentUser(request);
        String subject = user != null && user.getAccountId() != null
                ? "account:" + user.getAccountId() : "ip:" + clientIp(request);
        long minute = Instant.now().getEpochSecond() / 60;
        WindowCounter counter = counters.compute(category + ":" + subject, (key, current) ->
                current == null || current.minute != minute ? new WindowCounter(minute) : current);
        int used = counter.requests.incrementAndGet();
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
        if (used <= Math.max(1, limit)) {
            if (counters.size() > 10_000) {
                counters.entrySet().removeIf(entry -> entry.getValue().minute < minute - 2);
            }
            return true;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(60 - (Instant.now().getEpochSecond() % 60)));
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后重试\",\"data\":null}");
        return false;
    }

    private String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private static final class WindowCounter {
        private final long minute;
        private final AtomicInteger requests = new AtomicInteger();

        private WindowCounter(long minute) {
            this.minute = minute;
        }
    }
}
