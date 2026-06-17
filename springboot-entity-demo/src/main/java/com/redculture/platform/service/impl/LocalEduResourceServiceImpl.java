package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.mapper.LocalEduResourceMapper;
import com.redculture.platform.service.LocalEduResourceService;
import org.springframework.stereotype.Service;

@Service
public class LocalEduResourceServiceImpl extends ServiceImpl<LocalEduResourceMapper, LocalEduResource>
        implements LocalEduResourceService {
}
