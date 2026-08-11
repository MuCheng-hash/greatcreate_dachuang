package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.AgentActionOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentActionOutboxMapper extends BaseMapper<AgentActionOutbox> {

    @Insert("""
            INSERT IGNORE INTO agent_action_outbox(
                event_id, action_id, event_type, payload_json, status,
                attempt_count, next_attempt_at, created_at, updated_at
            ) VALUES(
                #{eventId}, #{actionId}, #{eventType}, CAST(#{payloadJson} AS JSON),
                'PENDING', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            """)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("actionId") String actionId,
                       @Param("eventType") String eventType,
                       @Param("payloadJson") String payloadJson);

    @Select("""
            SELECT * FROM agent_action_outbox
            WHERE status IN ('PENDING', 'RETRY')
              AND next_attempt_at <= CURRENT_TIMESTAMP
              AND (lease_expires_at IS NULL OR lease_expires_at < CURRENT_TIMESTAMP)
            ORDER BY created_at
            LIMIT #{batchSize}
            FOR UPDATE SKIP LOCKED
            """)
    List<AgentActionOutbox> selectClaimableForUpdate(@Param("batchSize") int batchSize);

    @Update("""
            UPDATE agent_action_outbox
            SET status = 'PROCESSING', lease_owner = #{leaseOwner},
                lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL #{leaseSeconds} SECOND),
                updated_at = CURRENT_TIMESTAMP
            WHERE event_id = #{eventId}
              AND status IN ('PENDING', 'RETRY')
            """)
    int markClaimed(@Param("eventId") String eventId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("leaseSeconds") int leaseSeconds);

    @Update("""
            UPDATE agent_action_outbox
            SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP,
                lease_owner = NULL, lease_expires_at = NULL,
                error_summary = NULL, updated_at = CURRENT_TIMESTAMP
            WHERE event_id = #{eventId} AND status = 'PROCESSING'
              AND lease_owner = #{leaseOwner}
            """)
    int markPublished(@Param("eventId") String eventId,
                      @Param("leaseOwner") String leaseOwner);

    @Update("""
            UPDATE agent_action_outbox
            SET status = 'RETRY', attempt_count = attempt_count + 1,
                next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL #{retrySeconds} SECOND),
                lease_owner = NULL, lease_expires_at = NULL,
                error_summary = #{errorSummary}, updated_at = CURRENT_TIMESTAMP
            WHERE event_id = #{eventId} AND status = 'PROCESSING'
              AND lease_owner = #{leaseOwner}
            """)
    int markRetry(@Param("eventId") String eventId,
                  @Param("leaseOwner") String leaseOwner,
                  @Param("retrySeconds") int retrySeconds,
                  @Param("errorSummary") String errorSummary);

    @Update("""
            UPDATE agent_action_outbox
            SET payload_json = JSON_OBJECT(), error_summary = NULL,
                payload_redacted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE status = 'PUBLISHED' AND published_at < #{cutoff}
              AND payload_redacted_at IS NULL
            LIMIT #{batchSize}
            """)
    int redactPublishedBefore(@Param("cutoff") java.time.LocalDateTime cutoff,
                              @Param("batchSize") int batchSize);
}
