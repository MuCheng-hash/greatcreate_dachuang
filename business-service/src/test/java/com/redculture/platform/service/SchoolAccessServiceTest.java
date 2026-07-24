package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchoolAccessServiceTest {

    @Test
    void schoolAccountCanOnlyAccessBoundSchool() {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(2L);
        SchoolAccessService service = new SchoolAccessService();

        assertDoesNotThrow(() -> service.requireSchoolAccess(2L, user));
        assertThrows(IllegalArgumentException.class, () -> service.requireSchoolAccess(3L, user));
    }

    @Test
    void platformAdminCanAccessAnySchool() {
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("platform_admin");

        assertDoesNotThrow(() -> new SchoolAccessService().requireSchoolAccess(99L, user));
    }
}
