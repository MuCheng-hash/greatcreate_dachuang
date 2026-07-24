package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import org.springframework.stereotype.Service;

@Service
public class SchoolAccessService {

    public AuthCurrentUserVO requireSchoolAccess(Long schoolId, AuthCurrentUserVO user) {
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
