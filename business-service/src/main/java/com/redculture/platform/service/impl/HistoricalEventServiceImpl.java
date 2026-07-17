package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.HistoricalEvent;
import com.redculture.platform.mapper.HistoricalEventMapper;
import com.redculture.platform.service.HistoricalEventService;
import org.springframework.stereotype.Service;

@Service
public class HistoricalEventServiceImpl
        extends ServiceImpl<HistoricalEventMapper, HistoricalEvent>
        implements HistoricalEventService {
}
