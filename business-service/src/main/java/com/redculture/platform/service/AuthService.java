package com.redculture.platform.service;

import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.AuthPasswordChangeRequest;
import com.redculture.platform.vo.request.AuthProfileUpdateRequest;
import com.redculture.platform.vo.request.AccountRegisterRequest;

public interface AuthService {

    void registerAccount(AccountRegisterRequest request);

    AuthCurrentUserVO login(AuthLoginRequest request);

    AuthCurrentUserVO currentUser(Long accountId);

    AuthCurrentUserVO updateProfile(AuthProfileUpdateRequest request, Long accountId);

    void changePassword(AuthPasswordChangeRequest request, Long accountId);

}
