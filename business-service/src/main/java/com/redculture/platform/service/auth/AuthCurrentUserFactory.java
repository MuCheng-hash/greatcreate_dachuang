package com.redculture.platform.service.auth;

import com.redculture.platform.entity.School;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.service.SchoolService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import org.springframework.stereotype.Component;

@Component
public class AuthCurrentUserFactory {

    private final SchoolService schoolService;

    public AuthCurrentUserFactory(SchoolService schoolService) {
        this.schoolService = schoolService;
    }

    public AuthCurrentUserVO build(SchoolUserAccount account) {
        AuthCurrentUserVO vo = new AuthCurrentUserVO();
        vo.setAccountId(account.getAccountId());
        vo.setUsername(account.getUsername());
        vo.setRoleCode(account.getRoleCode());
        vo.setSchoolId(account.getSchoolId());
        vo.setDisplayName(account.getDisplayName());
        vo.setContactName(account.getContactName());
        vo.setContactPhone(account.getContactPhone());
        vo.setLastLoginAt(account.getLastLoginAt());
        School school = account.getSchoolId() == null ? null : schoolService.getById(account.getSchoolId());
        if (school != null) {
            vo.setSchoolName(school.getSchoolName());
            vo.setSchoolLongitude(school.getLongitude());
            vo.setSchoolLatitude(school.getLatitude());
        }
        return vo;
    }
}
