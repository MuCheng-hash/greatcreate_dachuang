package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.HistoricalEvent;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HistoricalEventMapper extends BaseMapper<HistoricalEvent> {
}
