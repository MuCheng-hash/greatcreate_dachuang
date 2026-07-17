package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.RedStory;
import com.redculture.platform.mapper.RedStoryMapper;
import com.redculture.platform.service.RedStoryService;
import org.springframework.stereotype.Service;

@Service
public class RedStoryServiceImpl
        extends ServiceImpl<RedStoryMapper, RedStory>
        implements RedStoryService {
}
