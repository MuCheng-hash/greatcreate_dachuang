package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.AgentActionIdempotency;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AgentActionIdempotencyMapper extends BaseMapper<AgentActionIdempotency> {

    @Insert("""
            INSERT IGNORE INTO agent_action_idempotency(
                action_id, turn_id, operation, request_hash, request_json, status,
                created_at, updated_at
            ) VALUES(
                #{actionId}, #{turnId}, #{operation}, #{requestHash},
                CAST(#{requestJson} AS JSON), 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """)
    int insertIfAbsent(@Param("actionId") String actionId,
                       @Param("turnId") String turnId,
                       @Param("operation") String operation,
                       @Param("requestHash") String requestHash,
                       @Param("requestJson") String requestJson);

    @Select("SELECT * FROM agent_action_idempotency WHERE action_id = #{actionId} FOR UPDATE")
    AgentActionIdempotency selectForUpdate(@Param("actionId") String actionId);

    @Update("""
            UPDATE agent_action_idempotency
            SET request_json = NULL, response_json = NULL,
                payload_redacted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE status = 'SUCCEEDED' AND completed_at < #{cutoff}
              AND payload_redacted_at IS NULL
            LIMIT #{batchSize}
            """)
    int redactCompletedBefore(@Param("cutoff") LocalDateTime cutoff,
                              @Param("batchSize") int batchSize);
}
