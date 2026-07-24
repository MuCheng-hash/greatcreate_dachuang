package com.redculture.platform.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AuthProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtTokenServiceTest {

    private JwtTokenService service;
    private AuthCurrentUserVO user;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setJwtSecret("local-development-secret-with-at-least-32-bytes");
        properties.setAccessTokenTtlSeconds(900L);
        service = new JwtTokenService(new ObjectMapper(), properties);
        user = new AuthCurrentUserVO();
        user.setAccountId(7L);
        user.setRoleCode("school_admin");
        user.setSchoolId(3L);
    }

    @Test
    void issuesAndParsesAccessTokenWithAccountClaims() {
        String token = service.issueAccessToken(user);

        JwtTokenService.AccessTokenPrincipal principal = service.parseAccessToken(token);

        assertEquals(7L, principal.accountId());
        assertTrue(principal.expiresAt() > 0);
        assertTrue(token.split("\\.").length == 3);
    }

    @Test
    void rejectsTamperedAccessToken() {
        String token = service.issueAccessToken(user);
        int signatureStart = token.lastIndexOf('.') + 1;
        char signatureCharacter = token.charAt(signatureStart);
        char replacement = signatureCharacter == 'a' ? 'b' : 'a';
        String tampered = token.substring(0, signatureStart) + replacement
                + token.substring(signatureStart + 1);

        assertThrows(AuthTokenException.class, () -> service.parseAccessToken(tampered));
    }

    @Test
    void hashesRefreshTokenToDatabaseCompatibleSha256Hex() {
        String first = service.hashRefreshToken("refresh-1");
        String second = service.hashRefreshToken("refresh-2");

        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertNotEquals(first, second);
    }
}
