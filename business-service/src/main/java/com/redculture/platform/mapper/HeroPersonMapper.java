package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.HeroPerson;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface HeroPersonMapper extends BaseMapper<HeroPerson> {
}
