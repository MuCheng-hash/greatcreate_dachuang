package com.redculture.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.redculture.platform.mapper")
public class MybatisPlusConfig {
}
