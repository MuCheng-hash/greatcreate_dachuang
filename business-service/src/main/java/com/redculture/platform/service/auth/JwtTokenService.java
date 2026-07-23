package com.redculture.platform.service.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AuthProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class JwtTokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectMapper objectMapper;
    private final AuthProperties properties;

    public JwtTokenService(ObjectMapper objectMapper, AuthProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public String issueAccessToken(AuthCurrentUserVO user) {
        long issuedAt = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", String.valueOf(user.getAccountId()));
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("roleCode", user.getRoleCode());
        claims.put("schoolId", user.getSchoolId());
        claims.put("iat", issuedAt);
        claims.put("exp", issuedAt + Math.max(60L, properties.getAccessTokenTtlSeconds()));
        claims.put("tokenType", "access");

        String encodedHeader = encodeJson(header);
        String encodedClaims = encodeJson(claims);
        String unsigned = encodedHeader + "." + encodedClaims;
        return unsigned + "." + encodeBytes(sign(unsigned));
    }

    public AccessTokenPrincipal parseAccessToken(String token) {
        if (!StringUtils.hasText(token)) {
            throw new AuthTokenException("access token is missing");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new AuthTokenException("access token is invalid");
        }
        try {
            Map<String, Object> header = readJson(parts[0]);
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw new AuthTokenException("access token algorithm is invalid");
            }
            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new AuthTokenException("access token signature is invalid");
            }
            Map<String, Object> claims = readJson(parts[1]);
            long expiresAt = numberClaim(claims, "exp");
            if (expiresAt <= Instant.now().getEpochSecond()) {
                throw new AuthTokenException("access token is expired");
            }
            if (!"access".equals(claims.get("tokenType"))) {
                throw new AuthTokenException("access token type is invalid");
            }
            Long accountId = parseLong(claims.get("sub"));
            String jti = String.valueOf(claims.get("jti"));
            if (accountId == null || !StringUtils.hasText(jti)) {
                throw new AuthTokenException("access token claims are incomplete");
            }
            return new AccessTokenPrincipal(accountId, jti, expiresAt);
        } catch (AuthTokenException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthTokenException("access token is invalid");
        }
    }

    public String newRefreshToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String hashRefreshToken(String refreshToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(refreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot hash refresh token", exception);
        }
    }

    private byte[] sign(String value) {
        try {
            byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
            if (secret.length < 32) {
                throw new IllegalStateException("APP_AUTH_JWT_SECRET must contain at least 32 bytes");
            }
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("cannot sign access token", exception);
        }
    }

    private String encodeJson(Object value) {
        try {
            return encodeBytes(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot encode access token", exception);
        }
    }

    private Map<String, Object> readJson(String encoded) throws Exception {
        byte[] bytes = Base64.getUrlDecoder().decode(encoded);
        return objectMapper.readValue(bytes, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private long numberClaim(Map<String, Object> claims, String name) {
        Long value = parseLong(claims.get(name));
        if (value == null) {
            throw new AuthTokenException("access token claim is invalid: " + name);
        }
        return value;
    }

    private Long parseLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String encodeBytes(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public record AccessTokenPrincipal(Long accountId, String tokenId, long expiresAt) {
    }
}
