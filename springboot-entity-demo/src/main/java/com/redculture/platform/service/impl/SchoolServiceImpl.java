package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.School;
import com.redculture.platform.mapper.SchoolMapper;
import com.redculture.platform.service.SchoolService;
import org.springframework.stereotype.Service;

@Service
public class SchoolServiceImpl extends ServiceImpl<SchoolMapper, School> implements SchoolService {
}
