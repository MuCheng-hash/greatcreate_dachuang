package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.TeachingActivityPlan;
import com.redculture.platform.mapper.TeachingActivityPlanMapper;
import com.redculture.platform.service.TeachingActivityPlanService;
import org.springframework.stereotype.Service;

@Service
public class TeachingActivityPlanServiceImpl extends ServiceImpl<TeachingActivityPlanMapper, TeachingActivityPlan>
        implements TeachingActivityPlanService {
}
