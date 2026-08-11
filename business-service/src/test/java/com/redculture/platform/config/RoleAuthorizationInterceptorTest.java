package com.redculture.platform.config;

import com.redculture.platform.vo.AuthCurrentUserVO;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleAuthorizationInterceptorTest {

    private final RoleAuthorizationInterceptor interceptor = new RoleAuthorizationInterceptor();

    @Test
    void platformAdminInheritsCommonAndAdminPermissions() throws Exception {
        assertTrue(call("/api/ai/qa/stream", user("platform_admin", null)).allowed());
        assertTrue(call("/api/admin/schools", user("platform_admin", null)).allowed());
    }

    @Test
    void schoolAdminCannotUseAdminApiAndMustHaveSchool() throws Exception {
        assertTrue(call("/api/ai/qa/stream", user("school_admin", 8L)).allowed());
        Result admin = call("/api/admin/schools", user("school_admin", 8L));
        assertFalse(admin.allowed());
        assertEquals(403, admin.response().getStatus());
        assertFalse(call("/api/ai/qa/stream", user("school_admin", null)).allowed());
    }

    @Test
    void teacherCanUseSchoolApisButCannotUseAdminApiAndMustHaveSchool() throws Exception {
        assertTrue(call("/api/school-map/schools/1/detail", user("teacher", 1L)).allowed());
        assertTrue(call("/api/ai/qa/stream", user("teacher", 1L)).allowed());
        Result admin = call("/api/admin/schools", user("teacher", 1L));
        assertFalse(admin.allowed());
        assertEquals(403, admin.response().getStatus());
        assertFalse(call("/api/school-map/schools/1/detail", user("teacher", null)).allowed());
    }

    @Test
    void unknownRoleIsRejected() throws Exception {
        assertFalse(call("/api/ai/qa/stream", user("guest", null)).allowed());
    }

    @Test
    void teacherAndStudentCanOnlyUseTheirDedicatedApis() throws Exception {
        assertTrue(call("/api/teacher/classes/mine", user("teacher", 8L)).allowed());
        assertFalse(call("/api/student/class-tasks", user("teacher", 8L)).allowed());
        assertTrue(call("/api/student/class-tasks", user("student", 8L)).allowed());
        assertFalse(call("/api/teacher/classes/mine", user("student", 8L)).allowed());
    }

    private Result call(String path, AuthCurrentUserVO user) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setAttribute(AuthContext.CURRENT_USER_ATTRIBUTE, user);
        MockHttpServletResponse response = new MockHttpServletResponse();
        return new Result(interceptor.preHandle(request, response, new Object()), response);
    }

    private AuthCurrentUserVO user(String role, Long schoolId) {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode(role);
        user.setSchoolId(schoolId);
        return user;
    }

    private record Result(boolean allowed, MockHttpServletResponse response) {
    }
}
