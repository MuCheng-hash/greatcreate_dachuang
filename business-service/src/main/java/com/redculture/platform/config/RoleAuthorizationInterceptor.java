package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class RoleAuthorizationInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    private static final String PLATFORM_ADMIN = "platform_admin";
    private static final String SCHOOL_ADMIN = "school_admin";
    private static final String TEACHER = "teacher";
    private static final Set<String> COMMON_ROLES = Set.of(PLATFORM_ADMIN, SCHOOL_ADMIN, TEACHER);
    private static final Set<String> SCHOOL_SCOPED_ROLES = Set.of(SCHOOL_ADMIN, TEACHER);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        AuthCurrentUserVO user = AuthContext.currentUser(request);
        if (user == null) {
            return forbidden(response, "登录状态无效");
        }
        String role = user.getRoleCode();
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/teacher/") && !Set.of(PLATFORM_ADMIN, SCHOOL_ADMIN, "teacher").contains(role)) {
            return forbidden(response, "teacher access required");
        }
        boolean teacherAttachmentDownload = uri.startsWith("/api/student/attachments/") && "teacher".equals(role);
        if (uri.startsWith("/api/student/") && !"student".equals(role) && !teacherAttachmentDownload) {
            return forbidden(response, "student access required");
        }
        if (uri.startsWith("/api/teacher/") || uri.startsWith("/api/student/")) {
            if (user.getSchoolId() == null) return forbidden(response, "school account is required");
            return true;
        }
        if (!COMMON_ROLES.contains(role)) {
            return forbidden(response, "当前角色没有接口访问权限");
        }
        if (request.getRequestURI().startsWith("/api/admin/") && !PLATFORM_ADMIN.equals(role)) {
            return forbidden(response, "当前角色没有后台管理权限");
        }
        if (SCHOOL_SCOPED_ROLES.contains(role) && user.getSchoolId() == null) {
            return forbidden(response, "学校账号尚未绑定学校");
        }
        return true;
    }

    private boolean forbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
        return false;
    }
}
