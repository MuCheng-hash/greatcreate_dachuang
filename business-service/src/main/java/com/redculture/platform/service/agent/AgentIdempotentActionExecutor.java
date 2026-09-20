package com.redculture.platform.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.redculture.platform.entity.AgentActionIdempotency;
import com.redculture.platform.mapper.AgentActionIdempotencyMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class AgentIdempotentActionExecutor {

    public record Result(Map<String, Object> body, boolean replayed) {}

    private final AgentActionIdempotencyMapper mapper;
    private final ObjectMapper canonicalMapper;

    public AgentIdempotentActionExecutor(AgentActionIdempotencyMapper mapper,
                                         ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    @Transactional(transactionManager = "mysqlTransactionManager")
    public Result execute(String actionId,
                          String turnId,
                          String operation,
                          Object request,
                          Supplier<Map<String, Object>> mutation) {
        requireText(actionId, "Idempotency-Key 不能为空");
        requireText(turnId, "X-Agent-Turn-Id is required");
        requireText(operation, "operation is required");
        String requestJson = writeJson(request);
        String requestHash = sha256(operation + ":" + requestJson);
        mapper.insertIfAbsent(actionId, turnId, operation, requestHash, requestJson);
        AgentActionIdempotency record = mapper.selectForUpdate(actionId);
        if (record == null) {
            throw new IllegalStateException("未创建幂等记录");
        }
        if (!operation.equals(record.getOperation())
                || !requestHash.equals(record.getRequestHash())) {
            throw new IdempotencyConflictException(
                    "idempotency_conflict",
                    "Idempotency-Key 已被其他请求使用"
            );
        }
        if ("SUCCEEDED".equals(record.getStatus())) {
            return new Result(readMap(record.getResponseJson()), true);
        }
        if (!"PROCESSING".equals(record.getStatus())) {
            throw new IdempotencyConflictException(
                    "action_in_progress", "当前状态不允许执行该动作"
            );
        }
        Map<String, Object> body = new LinkedHashMap<>(mutation.get());
        record.setStatus("SUCCEEDED");
        record.setResponseJson(writeJson(body));
        record.setCompletedAt(LocalDateTime.now());
        mapper.updateById(record);
        return new Result(body, false);
    }

    private String writeJson(Object value) {
        try {
            return canonicalMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("动作载荷无法序列化", exception);
        }
    }

    private Map<String, Object> readMap(String value) {
        if (!StringUtils.hasText(value)) {
            return Map.of();
        }
        try {
            return canonicalMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已存储的幂等响应无效", exception);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
