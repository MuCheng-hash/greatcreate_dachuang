package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolRegistrationSubmitVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.SchoolRegisterRequest;
import jakarta.servlet.http.HttpSession;

public interface AuthService {

    SchoolRegistrationSubmitVO registerSchool(SchoolRegisterRequest request);

    AuthCurrentUserVO login(AuthLoginRequest request, HttpSession session);

    AuthCurrentUserVO currentUser(HttpSession session);

    void logout(HttpSession session);
}
