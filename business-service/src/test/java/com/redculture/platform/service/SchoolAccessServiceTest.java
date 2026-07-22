package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchoolAccessServiceTest {

    @Test
    void schoolAccountCanOnlyAccessBoundSchool() {
        AuthService authService = mock(AuthService.class);
        HttpSession session = mock(HttpSession.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("school_admin");
        user.setSchoolId(2L);
        when(authService.currentUser(session)).thenReturn(user);
        SchoolAccessService service = new SchoolAccessService(authService);

        assertDoesNotThrow(() -> service.requireSchoolAccess(2L, session));
        assertThrows(IllegalArgumentException.class, () -> service.requireSchoolAccess(3L, session));
    }

    @Test
    void platformAdminCanAccessAnySchool() {
        AuthService authService = mock(AuthService.class);
        HttpSession session = mock(HttpSession.class);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setRoleCode("platform_admin");
        when(authService.currentUser(session)).thenReturn(user);

        assertDoesNotThrow(() -> new SchoolAccessService(authService).requireSchoolAccess(99L, session));
    }
}
