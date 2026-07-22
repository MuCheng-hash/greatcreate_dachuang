package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

@Service
public class SchoolAccessService {

    private final AuthService authService;

    public SchoolAccessService(AuthService authService) {
        this.authService = authService;
    }

    public AuthCurrentUserVO requireSchoolAccess(Long schoolId, HttpSession session) {
        AuthCurrentUserVO user = authService.currentUser(session);
        if (user == null) {
            throw new IllegalArgumentException("authentication required");
        }
        if ("platform_admin".equals(user.getRoleCode())) {
            return user;
        }
        if (schoolId == null || !schoolId.equals(user.getSchoolId())) {
            throw new IllegalArgumentException("school access denied");
        }
        return user;
    }
}
