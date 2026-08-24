package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.TeacherRegistrationInviteVO;

import java.util.List;

public interface TeacherRegistrationInviteService {
    TeacherRegistrationInviteVO create(AuthCurrentUserVO user);

    List<TeacherRegistrationInviteVO> listMine(AuthCurrentUserVO user);

    void revoke(Long inviteId, AuthCurrentUserVO user);

    TeacherRegistrationInviteVO createForSchool(Long schoolId, AuthCurrentUserVO user);

    List<TeacherRegistrationInviteVO> listForSchool(Long schoolId, AuthCurrentUserVO user);
}
