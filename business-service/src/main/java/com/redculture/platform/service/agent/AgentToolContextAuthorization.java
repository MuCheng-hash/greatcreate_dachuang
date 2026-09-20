package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/** 签发和验证仅供本轮动态只读工具透传的短时可信上下文。 */
@Component
public class AgentToolContextAuthorization {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public AgentToolContextAuthorization(AgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 兼容不启动 Spring 容器的单元测试。 */
    public AgentToolContextAuthorization(AgentProperties properties) {
        this(properties, new ObjectMapper());
    }

    public String issue(Context context) {
        return issue(context, Instant.now());
    }

    public boolean configured() {
        return StringUtils.hasText(properties.getToolContextSigningSecret());
    }

    public String issue(Context context, Instant issuedAt) {
        requireSecret();
        if (context == null || context.user() == null || context.scopeType() == null
                || context.scopeId() == null || !StringUtils.hasText(context.clientTurnId())) {
            throw new IllegalArgumentException("Agent 工具授权上下文不完整");
        }
        AuthCurrentUserVO user = context.user();
        if (user.getAccountId() == null || !StringUtils.hasText(user.getRoleCode())) {
            throw new IllegalArgumentException("Agent 工具授权主体无效");
        }
        long expiresAt = issuedAt.plusSeconds(Math.max(1, properties.getToolContextAuthorizationTtlSeconds()))
                .getEpochSecond();
        Payload payload = new Payload(user.getAccountId(), user.getRoleCode(), user.getSchoolId(),
                context.scopeType().name(), context.scopeId(), context.clientTurnId(),
                List.copyOf(context.allowedTools() == null ? List.of() : context.allowedTools()),
                context.taskId(), context.resourceId(), expiresAt);
        try {
            byte[] encodedPayload = objectMapper.writeValueAsBytes(payload);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(encodedPayload)
                    + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sign(encodedPayload));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent 工具授权序列化失败", exception);
        }
    }

    public VerifiedContext verify(String token, String expectedTool) {
        return verify(token, expectedTool, Instant.now());
    }

    public VerifiedContext verify(String token, String expectedTool, Instant now) {
        requireSecret();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(expectedTool)) {
            throw new IllegalArgumentException("Agent 工具授权凭据无效");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Agent 工具授权凭据无效");
        }
        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[1]);
            if (!MessageDigest.isEqual(sign(payloadBytes), actualSignature)) {
                throw new IllegalArgumentException("Agent 工具授权签名无效");
            }
            Payload payload = objectMapper.readValue(payloadBytes, Payload.class);
            if (payload.expiresAtEpochSeconds() <= now.getEpochSecond()) {
                throw new IllegalArgumentException("Agent 工具授权已过期");
            }
            if (payload.accountId() == null || !StringUtils.hasText(payload.roleCode())
                    || !StringUtils.hasText(payload.scopeType()) || payload.scopeId() == null
                    || !StringUtils.hasText(payload.clientTurnId())
                    || payload.allowedTools() == null || !payload.allowedTools().contains(expectedTool)) {
                throw new IllegalArgumentException("Agent 工具授权范围无效");
            }
            KnowledgeScopeType scopeType = KnowledgeScopeType.from(payload.scopeType());
            if (scopeType == null) {
                throw new IllegalArgumentException("Agent 工具授权范围无效");
            }
            return new VerifiedContext(payload.accountId(), payload.roleCode(), payload.schoolId(),
                    scopeType, payload.scopeId(), payload.clientTurnId(), payload.taskId(), payload.resourceId());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Agent 工具授权凭据无效", exception);
        }
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(properties.getToolContextSigningSecret()
                    .getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception exception) {
            throw new IllegalStateException("Agent 工具授权签名失败", exception);
        }
    }

    private void requireSecret() {
        if (!configured()) {
            throw new IllegalStateException("Agent 工具授权签名密钥未配置");
        }
    }

    public record Context(AuthCurrentUserVO user, KnowledgeScopeType scopeType, Long scopeId,
                          String clientTurnId, List<String> allowedTools, Long taskId, Long resourceId) {
    }

    public record VerifiedContext(Long accountId, String roleCode, Long schoolId,
                                  KnowledgeScopeType scopeType, Long scopeId, String clientTurnId,
                                  Long taskId, Long resourceId) {
    }

    private record Payload(Long accountId, String roleCode, Long schoolId, String scopeType,
                           Long scopeId, String clientTurnId, List<String> allowedTools,
                           Long taskId, Long resourceId, long expiresAtEpochSeconds) {
    }
}
