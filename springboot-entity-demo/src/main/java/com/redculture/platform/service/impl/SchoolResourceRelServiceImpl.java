package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.mapper.SchoolResourceRelMapper;
import com.redculture.platform.service.SchoolResourceRelService;
import org.springframework.stereotype.Service;

@Service
public class SchoolResourceRelServiceImpl extends ServiceImpl<SchoolResourceRelMapper, SchoolResourceRel>
        implements SchoolResourceRelService {
}
