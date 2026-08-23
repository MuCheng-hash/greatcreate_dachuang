package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.AiTeachingPlanGeneration;
import com.redculture.platform.vo.TeachingPlanFeedbackReportItemVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReasonCountVO;
import com.redculture.platform.vo.TeachingPlanFeedbackReportSummaryVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

public interface AiTeachingPlanGenerationMapper extends BaseMapper<AiTeachingPlanGeneration> {

    @Select("SELECT * FROM ai_teaching_plan_generation WHERE generation_id = #{generationId} FOR UPDATE")
    AiTeachingPlanGeneration selectByIdForUpdate(@Param("generationId") Long generationId);

    @Select("""
            <script>
            SELECT COUNT(*) AS generation_count,
                   COALESCE(SUM(CASE WHEN f.feedback_id IS NOT NULL THEN 1 ELSE 0 END), 0) AS feedback_count,
                   COALESCE(SUM(CASE WHEN f.adopted = 1 THEN 1 ELSE 0 END), 0) AS adopted_count,
                   COALESCE(SUM(CASE WHEN f.feedback_id IS NOT NULL AND f.adopted = 0 THEN 1 ELSE 0 END), 0) AS not_adopted_count,
                   AVG(f.rating) AS average_rating,
                   COALESCE(SUM(CASE WHEN f.rating = 1 THEN 1 ELSE 0 END), 0) AS rating_one_count,
                   COALESCE(SUM(CASE WHEN f.rating = 2 THEN 1 ELSE 0 END), 0) AS rating_two_count,
                   COALESCE(SUM(CASE WHEN f.rating = 3 THEN 1 ELSE 0 END), 0) AS rating_three_count,
                   COALESCE(SUM(CASE WHEN f.rating = 4 THEN 1 ELSE 0 END), 0) AS rating_four_count,
                   COALESCE(SUM(CASE WHEN f.rating = 5 THEN 1 ELSE 0 END), 0) AS rating_five_count
            FROM ai_teaching_plan_generation g
            LEFT JOIN teaching_plan_feedback f ON f.generation_id = g.generation_id
            <where>
              g.actor_role = 'teacher'
              <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
              <if test="startAt != null">AND g.created_at &gt;= #{startAt}</if>
              <if test="endExclusive != null">AND g.created_at &lt; #{endExclusive}</if>
              <if test="theme != null and theme != ''">AND g.theme LIKE CONCAT('%', #{theme}, '%')</if>
              <if test="feedbackStatus == 'submitted'">AND f.feedback_id IS NOT NULL</if>
              <if test="feedbackStatus == 'pending'">AND f.feedback_id IS NULL</if>
              <if test="adopted != null">AND f.adopted = #{adopted}</if>
              <if test="lowScoreOnly">AND f.rating BETWEEN 1 AND 2</if>
              <if test="reasonCode != null and reasonCode != '' and reasonCode != 'UNSPECIFIED'">
                AND JSON_CONTAINS(COALESCE(f.reason_codes_json, JSON_ARRAY()), JSON_QUOTE(#{reasonCode}))
              </if>
              <if test="reasonCode == 'UNSPECIFIED'">
                AND f.feedback_id IS NOT NULL AND (f.adopted = 0 OR f.rating BETWEEN 1 AND 2)
                AND (f.reason_codes_json IS NULL OR JSON_LENGTH(f.reason_codes_json) = 0)
              </if>
            </where>
            </script>
            """)
    TeachingPlanFeedbackReportSummaryVO selectReportSummary(@Param("schoolId") Long schoolId,
                                                             @Param("startAt") LocalDateTime startAt,
                                                             @Param("endExclusive") LocalDateTime endExclusive,
                                                             @Param("theme") String theme,
                                                             @Param("feedbackStatus") String feedbackStatus,
                                                             @Param("adopted") Boolean adopted,
                                                             @Param("lowScoreOnly") boolean lowScoreOnly,
                                                             @Param("reasonCode") String reasonCode);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ai_teaching_plan_generation g
            LEFT JOIN teaching_plan_feedback f ON f.generation_id = g.generation_id
            <where>
              g.actor_role = 'teacher'
              <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
              <if test="startAt != null">AND g.created_at &gt;= #{startAt}</if>
              <if test="endExclusive != null">AND g.created_at &lt; #{endExclusive}</if>
              <if test="theme != null and theme != ''">AND g.theme LIKE CONCAT('%', #{theme}, '%')</if>
              <if test="feedbackStatus == 'submitted'">AND f.feedback_id IS NOT NULL</if>
              <if test="feedbackStatus == 'pending'">AND f.feedback_id IS NULL</if>
              <if test="adopted != null">AND f.adopted = #{adopted}</if>
              <if test="lowScoreOnly">AND f.rating BETWEEN 1 AND 2</if>
              <if test="reasonCode != null and reasonCode != '' and reasonCode != 'UNSPECIFIED'">
                AND JSON_CONTAINS(COALESCE(f.reason_codes_json, JSON_ARRAY()), JSON_QUOTE(#{reasonCode}))
              </if>
              <if test="reasonCode == 'UNSPECIFIED'">
                AND f.feedback_id IS NOT NULL AND (f.adopted = 0 OR f.rating BETWEEN 1 AND 2)
                AND (f.reason_codes_json IS NULL OR JSON_LENGTH(f.reason_codes_json) = 0)
              </if>
            </where>
            </script>
            """)
    long countReportRows(@Param("schoolId") Long schoolId,
                         @Param("startAt") LocalDateTime startAt,
                         @Param("endExclusive") LocalDateTime endExclusive,
                         @Param("theme") String theme,
                         @Param("feedbackStatus") String feedbackStatus,
                         @Param("adopted") Boolean adopted,
                         @Param("lowScoreOnly") boolean lowScoreOnly,
                         @Param("reasonCode") String reasonCode);

    @Select("""
            <script>
            SELECT g.generation_id, g.school_id, s.school_name, g.account_id,
                   COALESCE(tp.teacher_name, a.display_name, a.username) AS teacher_name,
                   g.theme, g.grade, g.activity_type, g.generation_status,
                   g.retrieval_status, g.llm_provider, g.llm_model,
                   g.saved_plan_id, g.response_json, g.created_at,
                   f.feedback_id, f.adopted, f.rating, f.reason_codes_json, f.teacher_note, f.submitted_at
            FROM ai_teaching_plan_generation g
            LEFT JOIN teaching_plan_feedback f ON f.generation_id = g.generation_id
            LEFT JOIN school s ON s.school_id = g.school_id
            LEFT JOIN teacher_profile tp ON tp.account_id = g.account_id
            LEFT JOIN school_user_account a ON a.account_id = g.account_id
            <where>
              g.actor_role = 'teacher'
              <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
              <if test="startAt != null">AND g.created_at &gt;= #{startAt}</if>
              <if test="endExclusive != null">AND g.created_at &lt; #{endExclusive}</if>
              <if test="theme != null and theme != ''">AND g.theme LIKE CONCAT('%', #{theme}, '%')</if>
              <if test="feedbackStatus == 'submitted'">AND f.feedback_id IS NOT NULL</if>
              <if test="feedbackStatus == 'pending'">AND f.feedback_id IS NULL</if>
              <if test="adopted != null">AND f.adopted = #{adopted}</if>
              <if test="lowScoreOnly">AND f.rating BETWEEN 1 AND 2</if>
              <if test="reasonCode != null and reasonCode != '' and reasonCode != 'UNSPECIFIED'">
                AND JSON_CONTAINS(COALESCE(f.reason_codes_json, JSON_ARRAY()), JSON_QUOTE(#{reasonCode}))
              </if>
              <if test="reasonCode == 'UNSPECIFIED'">
                AND f.feedback_id IS NOT NULL AND (f.adopted = 0 OR f.rating BETWEEN 1 AND 2)
                AND (f.reason_codes_json IS NULL OR JSON_LENGTH(f.reason_codes_json) = 0)
              </if>
            </where>
            ORDER BY g.created_at DESC, g.generation_id DESC
            LIMIT #{pageSize} OFFSET #{offset}
            </script>
            """)
    List<TeachingPlanFeedbackReportItemVO> selectReportRows(@Param("schoolId") Long schoolId,
                                                            @Param("startAt") LocalDateTime startAt,
                                                            @Param("endExclusive") LocalDateTime endExclusive,
                                                            @Param("theme") String theme,
                                                            @Param("feedbackStatus") String feedbackStatus,
                                                            @Param("adopted") Boolean adopted,
                                                            @Param("lowScoreOnly") boolean lowScoreOnly,
                                                            @Param("reasonCode") String reasonCode,
                                                            @Param("offset") long offset,
                                                            @Param("pageSize") long pageSize);

    @Select("""
            <script>
            SELECT reasons.reason_code, COUNT(*) AS reason_count
            FROM ai_teaching_plan_generation g
            JOIN teaching_plan_feedback f ON f.generation_id = g.generation_id
            JOIN JSON_TABLE(COALESCE(f.reason_codes_json, JSON_ARRAY()), '$[*]'
                 COLUMNS(reason_code VARCHAR(64) PATH '$')) reasons
            <where>
              g.actor_role = 'teacher'
              <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
              <if test="startAt != null">AND g.created_at &gt;= #{startAt}</if>
              <if test="endExclusive != null">AND g.created_at &lt; #{endExclusive}</if>
              <if test="theme != null and theme != ''">AND g.theme LIKE CONCAT('%', #{theme}, '%')</if>
              <if test="feedbackStatus == 'submitted'">AND f.feedback_id IS NOT NULL</if>
              <if test="feedbackStatus == 'pending'">AND f.feedback_id IS NULL</if>
              <if test="adopted != null">AND f.adopted = #{adopted}</if>
              <if test="lowScoreOnly">AND f.rating BETWEEN 1 AND 2</if>
              <if test="reasonCode != null and reasonCode != '' and reasonCode != 'UNSPECIFIED'">
                AND JSON_CONTAINS(COALESCE(f.reason_codes_json, JSON_ARRAY()), JSON_QUOTE(#{reasonCode}))
              </if>
              <if test="reasonCode == 'UNSPECIFIED'">AND 1 = 0</if>
            </where>
            GROUP BY reasons.reason_code
            </script>
            """)
    List<TeachingPlanFeedbackReasonCountVO> selectReasonCounts(@Param("schoolId") Long schoolId,
                                                               @Param("startAt") LocalDateTime startAt,
                                                               @Param("endExclusive") LocalDateTime endExclusive,
                                                               @Param("theme") String theme,
                                                               @Param("feedbackStatus") String feedbackStatus,
                                                               @Param("adopted") Boolean adopted,
                                                               @Param("lowScoreOnly") boolean lowScoreOnly,
                                                               @Param("reasonCode") String reasonCode);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM ai_teaching_plan_generation g
            JOIN teaching_plan_feedback f ON f.generation_id = g.generation_id
            <where>
              g.actor_role = 'teacher'
              AND (f.adopted = 0 OR f.rating BETWEEN 1 AND 2)
              AND (f.reason_codes_json IS NULL OR JSON_LENGTH(f.reason_codes_json) = 0)
              <if test="schoolId != null">AND g.school_id = #{schoolId}</if>
              <if test="startAt != null">AND g.created_at &gt;= #{startAt}</if>
              <if test="endExclusive != null">AND g.created_at &lt; #{endExclusive}</if>
              <if test="theme != null and theme != ''">AND g.theme LIKE CONCAT('%', #{theme}, '%')</if>
              <if test="feedbackStatus == 'pending'">AND 1 = 0</if>
              <if test="adopted != null">AND f.adopted = #{adopted}</if>
              <if test="lowScoreOnly">AND f.rating BETWEEN 1 AND 2</if>
              <if test="reasonCode != null and reasonCode != '' and reasonCode != 'UNSPECIFIED'">AND 1 = 0</if>
            </where>
            </script>
            """)
    long countUnspecifiedReasons(@Param("schoolId") Long schoolId,
                                 @Param("startAt") LocalDateTime startAt,
                                 @Param("endExclusive") LocalDateTime endExclusive,
                                 @Param("theme") String theme,
                                 @Param("feedbackStatus") String feedbackStatus,
                                 @Param("adopted") Boolean adopted,
                                 @Param("lowScoreOnly") boolean lowScoreOnly,
                                 @Param("reasonCode") String reasonCode);
}
