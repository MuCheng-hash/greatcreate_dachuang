package com.redculture.platform.service.auth;

import com.redculture.platform.config.AuthProperties;
import com.redculture.platform.entity.AuthRefreshToken;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.mapper.AuthRefreshTokenMapper;
import com.redculture.platform.mapper.SchoolUserAccountMapper;
import com.redculture.platform.service.auth.AuthTokenService.IssuedTokens;
import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthTokenServiceTest {

    private final AuthRefreshTokenMapper refreshTokenMapper = mock(AuthRefreshTokenMapper.class);
    private final SchoolUserAccountMapper accountMapper = mock(SchoolUserAccountMapper.class);
    private final AuthCurrentUserFactory userFactory = mock(AuthCurrentUserFactory.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final AuthProperties properties = new AuthProperties();
    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final List<AuthRefreshToken> inserted = new ArrayList<>();
    private AuthTokenService service;
    private AuthCurrentUserVO user;
    private SchoolUserAccount account;

    @BeforeEach
    void setUp() {
        service = new AuthTokenService(
                refreshTokenMapper, accountMapper, userFactory, jwtTokenService, properties
        );
        user = new AuthCurrentUserVO();
        user.setAccountId(7L);
        user.setRoleCode("school_admin");
        user.setSchoolId(3L);
        account = new SchoolUserAccount();
        account.setAccountId(7L);
        account.setStatus(AccountStatus.ACTIVE);

        when(jwtTokenService.newRefreshToken()).thenReturn(
                "refresh-1", "csrf-1", "refresh-2", "csrf-2"
        );
        when(jwtTokenService.hashRefreshToken(anyString()))
                .thenAnswer(invocation -> "hash-" + invocation.getArgument(0, String.class));
        when(jwtTokenService.issueAccessToken(any())).thenReturn("access-1", "access-2");
        when(accountMapper.selectById(7L)).thenReturn(account);
        when(userFactory.build(account)).thenReturn(user);
        doAnswer(invocation -> {
            inserted.add(invocation.getArgument(0, AuthRefreshToken.class));
            return 1;
        }).when(refreshTokenMapper).insert(any(AuthRefreshToken.class));
        when(refreshTokenMapper.selectOne(any())).thenAnswer(invocation ->
                inserted.isEmpty() ? null : inserted.get(inserted.size() - 1)
        );
        when(refreshTokenMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void rotatesRefreshTokenAndInvalidatesThePreviousRecord() {
        IssuedTokens first = service.issue(user, request);
        AuthRefreshToken oldRecord = inserted.get(0);

        IssuedTokens second = service.rotate(first.refreshToken(), request);

        assertEquals("refresh-1", first.refreshToken());
        assertEquals("refresh-2", second.refreshToken());
        assertEquals(oldRecord.getTokenFamilyId(), inserted.get(1).getTokenFamilyId());
        assertNotNull(oldRecord.getRevokedAt());
        assertEquals("rotated", oldRecord.getRevokeReason());
        verify(refreshTokenMapper).updateById(oldRecord);
    }

    @Test
    void reusingRotatedTokenRevokesItsWholeFamily() {
        IssuedTokens first = service.issue(user, request);
        AuthRefreshToken oldRecord = inserted.get(0);
        service.rotate(first.refreshToken(), request);
        AuthRefreshToken rotatedRecord = inserted.get(1);
        rotatedRecord.setRevokedAt(java.time.LocalDateTime.now());
        when(refreshTokenMapper.selectOne(any())).thenReturn(oldRecord);
        when(refreshTokenMapper.selectList(any())).thenReturn(List.of(rotatedRecord));

        assertThrows(AuthTokenException.class, () -> service.rotate(first.refreshToken(), request));

        verify(refreshTokenMapper).updateById(rotatedRecord);
        assertEquals("refresh_token_reuse", rotatedRecord.getRevokeReason());
    }

    @Test
    void refusesRefreshForDisabledAccount() {
        IssuedTokens first = service.issue(user, request);
        account.setStatus(AccountStatus.DISABLED);

        assertThrows(AuthTokenException.class, () -> service.rotate(first.refreshToken(), request));
    }
}
