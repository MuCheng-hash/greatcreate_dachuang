package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.TeachingPlanFeedback;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TeachingPlanFeedbackMapper extends BaseMapper<TeachingPlanFeedback> {

    @Select("SELECT * FROM teaching_plan_feedback WHERE generation_id = #{generationId} FOR UPDATE")
    TeachingPlanFeedback selectByGenerationIdForUpdate(@Param("generationId") Long generationId);
}
