package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 提供 Servlet 请求中当前认证用户的读取与必需性校验能力。
 */
public final class AuthContext {

    /**
     * Servlet 请求中保存当前认证用户的属性键。
     */
    public static final String CURRENT_USER_ATTRIBUTE = AuthContext.class.getName() + ".CURRENT_USER";

    private AuthContext() {
    }

    /**
     * 读取请求上下文中的当前认证用户。
     *
     * @param request 当前 HTTP 请求
     * @return 当前认证用户；请求为空或未写入用户时返回 {@code null}
     */
    public static AuthCurrentUserVO currentUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(CURRENT_USER_ATTRIBUTE);
        return value instanceof AuthCurrentUserVO user ? user : null;
    }

    /**
     * 读取当前认证用户，并在未认证时拒绝请求。
     *
     * @param request 当前 HTTP 请求
     * @return 当前认证用户
     * @throws IllegalArgumentException 当前请求未认证时抛出
     */
    public static AuthCurrentUserVO requireUser(HttpServletRequest request) {
        AuthCurrentUserVO user = currentUser(request);
        if (user == null) {
            throw new IllegalArgumentException("authentication required");
        }
        return user;
    }
}
