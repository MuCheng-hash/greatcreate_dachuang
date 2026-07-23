package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.service.auth.AuthCookieManager;
import com.redculture.platform.service.auth.AuthTokenException;
import com.redculture.platform.service.auth.AuthTokenService;
import com.redculture.platform.config.AuthContext;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolRegistrationSubmitVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.AuthPasswordChangeRequest;
import com.redculture.platform.vo.request.AuthProfileUpdateRequest;
import com.redculture.platform.vo.request.SchoolRegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthTokenService authTokenService;
    private final AuthCookieManager cookieManager;

    public AuthController(AuthService authService,
                          AuthTokenService authTokenService,
                          AuthCookieManager cookieManager) {
        this.authService = authService;
        this.authTokenService = authTokenService;
        this.cookieManager = cookieManager;
    }

    @PostMapping("/school-register")
    public ApiResponse<SchoolRegistrationSubmitVO> schoolRegister(@RequestBody SchoolRegisterRequest request) {
        return ApiResponse.success("registration submitted", authService.registerSchool(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthCurrentUserVO> login(@RequestBody AuthLoginRequest request,
                                               HttpServletRequest servletRequest,
                                               HttpServletResponse servletResponse) {
        AuthCurrentUserVO user = authService.login(request);
        cookieManager.write(servletResponse, authTokenService.issue(user, servletRequest));
        return ApiResponse.success("login success", user);
    }

    @GetMapping("/me")
    public ApiResponse<AuthCurrentUserVO> currentUser(HttpServletRequest request,
                                                      HttpServletResponse response) {
        AuthCurrentUserVO user = AuthContext.currentUser(request);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return ApiResponse.fail(401, "请先登录");
        }
        return ApiResponse.success(user);
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthCurrentUserVO> refresh(HttpServletRequest request,
                                                  HttpServletResponse response) {
        try {
            String refreshToken = cookieManager.read(request, cookieManager.refreshCookieName());
            AuthTokenService.IssuedTokens tokens = authTokenService.rotate(refreshToken, request);
            cookieManager.write(response, tokens);
            return ApiResponse.success("token refreshed", tokens.user());
        } catch (AuthTokenException exception) {
            cookieManager.clear(response);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return ApiResponse.fail(401, exception.getMessage());
        }
    }

    @PutMapping("/profile")
    public ApiResponse<AuthCurrentUserVO> updateProfile(@RequestBody AuthProfileUpdateRequest request,
                                                        HttpServletRequest servletRequest) {
        try {
            return ApiResponse.success("profile updated", authService.updateProfile(
                    request, AuthContext.requireUser(servletRequest).getAccountId()));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PutMapping("/password")
    public ApiResponse<Boolean> changePassword(@RequestBody AuthPasswordChangeRequest request,
                                               HttpServletRequest servletRequest) {
        try {
            authService.changePassword(request, AuthContext.requireUser(servletRequest).getAccountId());
            return ApiResponse.success("password updated", true);
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpServletRequest request, HttpServletResponse response) {
        authTokenService.logout(cookieManager.read(request, cookieManager.refreshCookieName()));
        cookieManager.clear(response);
        return ApiResponse.success("logout success", true);
    }
}
