package com.redculture.platform.controller;

import com.redculture.platform.common.ApiResponse;
import com.redculture.platform.service.AuthService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolRegistrationSubmitVO;
import com.redculture.platform.vo.request.AuthLoginRequest;
import com.redculture.platform.vo.request.SchoolRegisterRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/school-register")
    public ApiResponse<SchoolRegistrationSubmitVO> schoolRegister(@RequestBody SchoolRegisterRequest request) {
        try {
            return ApiResponse.success("registration submitted", authService.registerSchool(request));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @PostMapping("/login")
    public ApiResponse<AuthCurrentUserVO> login(@RequestBody AuthLoginRequest request, HttpSession session) {
        try {
            return ApiResponse.success("login success", authService.login(request, session));
        } catch (IllegalArgumentException exception) {
            return ApiResponse.fail(exception.getMessage());
        }
    }

    @GetMapping("/me")
    public ApiResponse<AuthCurrentUserVO> currentUser(HttpSession session) {
        return ApiResponse.success(authService.currentUser(session));
    }

    @PostMapping("/logout")
    public ApiResponse<Boolean> logout(HttpSession session) {
        authService.logout(session);
        return ApiResponse.success("logout success", true);
    }
}
