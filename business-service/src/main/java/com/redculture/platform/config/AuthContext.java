package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;

public final class AuthContext {

    public static final String CURRENT_USER_ATTRIBUTE = AuthContext.class.getName() + ".CURRENT_USER";

    private AuthContext() {
    }

    public static AuthCurrentUserVO currentUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(CURRENT_USER_ATTRIBUTE);
        return value instanceof AuthCurrentUserVO user ? user : null;
    }

    public static AuthCurrentUserVO requireUser(HttpServletRequest request) {
        AuthCurrentUserVO user = currentUser(request);
        if (user == null) {
            throw new IllegalArgumentException("authentication required");
        }
        return user;
    }
}
