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
            // 只从约定的访问令牌 Cookie 中读取凭证，避免接受不受控的请求参数。
            String rawToken = cookieManager.read(request, cookieManager.accessCookieName());
            if (StringUtils.hasText(rawToken)) {
                // 解析并校验访问令牌；令牌过期、签名非法等情况会抛出 AuthTokenException。
                JwtTokenService.AccessTokenPrincipal principal = jwtTokenService.parseAccessToken(rawToken);

                // JWT 中仅保存账号标识，当前用户信息仍以服务端查询结果为准，
                // 从而避免已禁用或已不存在的账号凭借旧令牌继续访问。
                AuthCurrentUserVO user = authService.currentUser(principal.accountId());
                if (user != null) {
                    // 将已认证用户绑定到本次请求，后续 Controller 或
                    // 业务层可通过AuthContext.CURRENT_USER_ATTRIBUTE 获取，无需重复解析令牌。
                    request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, user);

                    // 用户存在且认证完成，放行请求并继续执行目标处理器。
                    return true;
                }
            }
        } catch (AuthTokenException ignored) {
            // 令牌无效时不向客户端暴露具体原因，统一在下方返回规范化的 401 响应。
        }

        // Cookie 缺失或为空、用户不存在，或令牌校验失败时，终止本次请求。
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        // 明确 UTF-8 编码，保证中文错误信息在不同 Servlet 容器中均可正确解码。
        response.setCharacterEncoding("UTF-8");
        // 声明 JSON 响应类型和字符集，使前端按统一 API 错误结构解析响应体。
        response.setContentType("application/json;charset=UTF-8");
        // 响应已由拦截器直接写出，因此必须返回 false，阻止 Controller 继续执行。
        response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
        return false;
    }
}
