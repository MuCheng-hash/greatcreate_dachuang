package com.redculture.platform.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.redculture.platform.config.AuthProperties;
import com.redculture.platform.entity.AuthRefreshToken;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.mapper.AuthRefreshTokenMapper;
import com.redculture.platform.mapper.SchoolUserAccountMapper;
import com.redculture.platform.vo.AuthCurrentUserVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class AuthTokenService {

    private final AuthRefreshTokenMapper refreshTokenMapper;
    private final SchoolUserAccountMapper accountMapper;
    private final AuthCurrentUserFactory userFactory;
    private final JwtTokenService jwtTokenService;
    private final AuthProperties properties;

    public AuthTokenService(AuthRefreshTokenMapper refreshTokenMapper,
                            SchoolUserAccountMapper accountMapper,
                            AuthCurrentUserFactory userFactory,
                            JwtTokenService jwtTokenService,
                            AuthProperties properties) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.accountMapper = accountMapper;
        this.userFactory = userFactory;
        this.jwtTokenService = jwtTokenService;
        this.properties = properties;
    }

    public IssuedTokens issue(AuthCurrentUserVO user, HttpServletRequest request) {
        return issue(user, request, UUID.randomUUID().toString());
    }

    public IssuedTokens rotate(String rawRefreshToken, HttpServletRequest request) {
        if (!StringUtils.hasText(rawRefreshToken)) {
            throw new AuthTokenException("refresh token is missing");
        }
        AuthRefreshToken current = findByHash(rawRefreshToken);
        if (current == null) {
            throw new AuthTokenException("refresh token is invalid");
        }
        if (current.getRevokedAt() != null) {
            revokeFamily(current.getTokenFamilyId(), "refresh_token_reuse");
            throw new AuthTokenException("refresh token has already been used");
        }
        LocalDateTime now = LocalDateTime.now();
        if (current.getExpiresAt() == null || !current.getExpiresAt().isAfter(now)) {
            revoke(current, "refresh_token_expired");
            throw new AuthTokenException("refresh token is expired");
        }

        SchoolUserAccount account = accountMapper.selectById(current.getAccountId());
        if (account == null || account.getStatus() != AccountStatus.ACTIVE) {
            revokeFamily(current.getTokenFamilyId(), "account_inactive");
            throw new AuthTokenException("account is not active");
        }

        revoke(current, "rotated");
        return issue(userFactory.build(account), request, current.getTokenFamilyId());
    }

    public void logout(String rawRefreshToken) {
        if (!StringUtils.hasText(rawRefreshToken)) {
            return;
        }
        AuthRefreshToken current = findByHash(rawRefreshToken);
        if (current != null && current.getRevokedAt() == null) {
            revoke(current, "logout");
        }
    }

    public void revokeAll(Long accountId, String reason) {
        if (accountId == null) {
            return;
        }
        List<AuthRefreshToken> tokens = refreshTokenMapper.selectList(new QueryWrapper<AuthRefreshToken>()
                .eq("account_id", accountId)
                .isNull("revoked_at"));
        for (AuthRefreshToken token : tokens) {
            revoke(token, reason);
        }
    }

    private IssuedTokens issue(AuthCurrentUserVO user, HttpServletRequest request, String familyId) {
        String rawRefreshToken = jwtTokenService.newRefreshToken();
        LocalDateTime now = LocalDateTime.now();
        AuthRefreshToken record = new AuthRefreshToken();
        record.setAccountId(user.getAccountId());
        record.setTokenHash(jwtTokenService.hashRefreshToken(rawRefreshToken));
        record.setTokenFamilyId(familyId);
        record.setIssuedAt(now);
        record.setExpiresAt(now.plusSeconds(Math.max(300L, properties.getRefreshTokenTtlSeconds())));
        record.setUserAgent(trim(request == null ? null : request.getHeader("User-Agent"), 512));
        record.setClientIp(trim(request == null ? null : request.getRemoteAddr(), 45));
        refreshTokenMapper.insert(record);
        String csrfToken = jwtTokenService.newRefreshToken();
        return new IssuedTokens(user, jwtTokenService.issueAccessToken(user), rawRefreshToken, csrfToken);
    }

    private AuthRefreshToken findByHash(String rawRefreshToken) {
        return refreshTokenMapper.selectOne(new QueryWrapper<AuthRefreshToken>()
                .eq("token_hash", jwtTokenService.hashRefreshToken(rawRefreshToken))
                .last("LIMIT 1"));
    }

    private void revokeFamily(String familyId, String reason) {
        if (!StringUtils.hasText(familyId)) {
            return;
        }
        List<AuthRefreshToken> tokens = refreshTokenMapper.selectList(new QueryWrapper<AuthRefreshToken>()
                .eq("token_family_id", familyId)
                .isNull("revoked_at"));
        for (AuthRefreshToken token : tokens) {
            revoke(token, reason);
        }
    }

    private void revoke(AuthRefreshToken token, String reason) {
        LocalDateTime now = LocalDateTime.now();
        token.setRevokedAt(now);
        token.setRotatedAt("rotated".equals(reason) ? now : token.getRotatedAt());
        token.setRevokeReason(trim(reason, 100));
        refreshTokenMapper.updateById(token);
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    public record IssuedTokens(AuthCurrentUserVO user,
                               String accessToken,
                               String refreshToken,
                               String csrfToken) {
    }
}
