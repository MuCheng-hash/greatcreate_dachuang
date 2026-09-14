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

/**
 * 按请求主体和接口类型实施固定分钟窗口限流。
 */
@Component
public class RequestRateLimitInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    /**
     * 接口限流配置。
     */
    private final RateLimitProperties properties;
    /**
     * 按请求主体与接口类别维护的分钟窗口计数器。
     */
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /**
     * 创建请求频率限制拦截器。
     *
     * @param properties 相关配置属性
     */
    public RequestRateLimitInterceptor(RateLimitProperties properties) {
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

    /**
     * 记录单个请求主体在一个自然分钟窗口内的访问次数。
     */
    private static final class WindowCounter {
        /**
         * 计数窗口对应的 Unix 分钟编号。
         */
        private final long minute;
        /**
         * 当前窗口内累计的请求次数。
         */
        private final AtomicInteger requests = new AtomicInteger();

        private WindowCounter(long minute) {
            this.minute = minute;
        }
    }
}
