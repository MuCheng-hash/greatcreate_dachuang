package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.TeachingActivityPlanResource;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TeachingActivityPlanResourceMapper extends BaseMapper<TeachingActivityPlanResource> {
    @Delete("DELETE FROM teaching_activity_plan_resource WHERE plan_id = #{planId}")
    int deleteByPlanId(Long planId);

    @Select("SELECT resource_id FROM teaching_activity_plan_resource WHERE plan_id = #{planId} ORDER BY sort_order, resource_id")
    List<Long> selectResourceIds(Long planId);
}
