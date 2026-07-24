package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.AuthRefreshToken;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuthRefreshTokenMapper extends BaseMapper<AuthRefreshToken> {
}
