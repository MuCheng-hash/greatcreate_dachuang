package com.redculture.platform.exception;

import com.redculture.platform.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthExceptionHandlerTest {

    @Test
    void conflictUsesHttpAndBusinessStatus409() {
        ResponseEntity<ApiResponse<Void>> response = new AuthExceptionHandler()
                .handleConflict(new AuthConflictException("账号已存在"));

        assertEquals(409, response.getStatusCode().value());
        assertEquals(409, response.getBody().getCode());
        assertEquals("账号已存在", response.getBody().getMessage());
    }

    @Test
    void invalidArgumentsUseHttpAndBusinessStatus400() {
        ResponseEntity<ApiResponse<Void>> response = new AuthExceptionHandler()
                .handleBadRequest(new IllegalArgumentException("username is required"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(400, response.getBody().getCode());
    }
}
