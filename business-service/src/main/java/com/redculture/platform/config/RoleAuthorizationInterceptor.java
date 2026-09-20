package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 根据请求路径、当前角色和学校归属执行访问授权。
 */
@Component
public class RoleAuthorizationInterceptor implements org.springframework.web.servlet.HandlerInterceptor {

    /**
     * 平台管理员角色编码。
     */
    private static final String PLATFORM_ADMIN = "platform_admin";
    /**
     * 学校管理员角色编码。
     */
    private static final String SCHOOL_ADMIN = "school_admin";
    /**
     * 教师角色编码。
     */
    private static final String TEACHER = "teacher";
    /**
     * 学生角色编码。
     */
    private static final String STUDENT = "student";
    /**
     * 允许访问教师端通用接口的角色集合。
     */
    private static final Set<String> COMMON_ROLES = Set.of(PLATFORM_ADMIN, SCHOOL_ADMIN, TEACHER);
    /**
     * 需要校验学校数据范围的角色集合。
     */
    private static final Set<String> SCHOOL_SCOPED_ROLES = Set.of(SCHOOL_ADMIN, TEACHER);
    /**
     * 允许使用 Agent 记忆功能的角色集合。
     */
    private static final Set<String> MEMORY_USER_ROLES = Set.of(SCHOOL_ADMIN, TEACHER, STUDENT);
    /**
     * 平台认可的全部已认证角色集合。
     */
    private static final Set<String> AUTHENTICATED_ROLES = Set.of(PLATFORM_ADMIN, SCHOOL_ADMIN, TEACHER, STUDENT);

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
        AuthCurrentUserVO user = AuthContext.currentUser(request);
        if (user == null) {
            return forbidden(response, "登录状态无效");
        }
        String role = user.getRoleCode();
        String uri = request.getRequestURI();
        // 登录用户需要在前端路由切换时读取自身信息；学生也拥有个人资料读写权限。
        if (uri.startsWith("/api/auth/") && AUTHENTICATED_ROLES.contains(role)) {
            return true;
        }
        if (isMemoryEndpoint(uri) && MEMORY_USER_ROLES.contains(role)) {
            if (user.getSchoolId() == null) return forbidden(response, "需要学校账号");
            return true;
        }
        // 学生端需要读取地图、学校公开资源以及问答历史；具体写操作仍由业务层校验。
        boolean studentReadEndpoint = STUDENT.equals(role)
                && ("GET".equalsIgnoreCase(request.getMethod())
                    && (uri.startsWith("/api/map/") || uri.startsWith("/api/school-map/")
                    || uri.startsWith("/api/ai/") || "/api/teacher/resources/nearby".equals(uri))
                    || ("POST".equalsIgnoreCase(request.getMethod())
                    && ("/api/ai/qa/ask".equals(uri)
                    || "/api/ai/qa/stream".equals(uri)
                    || uri.matches("/api/ai/qa/turns/[^/]+/cancel"))));
        if (studentReadEndpoint && user.getSchoolId() != null) return true;
        if (uri.startsWith("/api/teacher/") && !Set.of(PLATFORM_ADMIN, SCHOOL_ADMIN, TEACHER).contains(role)
                && !(STUDENT.equals(role) && "/api/teacher/resources/nearby".equals(uri))) {
            return forbidden(response, "需要教师权限");
        }
        boolean teacherAttachmentDownload = uri.startsWith("/api/student/attachments/") && TEACHER.equals(role);
        if (uri.startsWith("/api/student/") && !STUDENT.equals(role) && !teacherAttachmentDownload) {
            return forbidden(response, "需要学生权限");
        }
        if (uri.startsWith("/api/teacher/") || uri.startsWith("/api/student/")) {
            if (user.getSchoolId() == null) return forbidden(response, "需要学校账号");
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

    private boolean isMemoryEndpoint(String uri) {
        return "/api/ai/memory-settings".equals(uri) || uri.startsWith("/api/ai/memories");
    }

    private boolean forbidden(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":403,\"message\":\"" + message + "\",\"data\":null}");
        return false;
    }
}
