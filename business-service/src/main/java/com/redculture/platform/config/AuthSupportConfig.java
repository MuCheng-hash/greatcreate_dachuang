package com.redculture.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 注册密码编码器并启用认证相关配置属性。
 */
@Configuration
@EnableConfigurationProperties({AdminAccountProperties.class, AuthProperties.class, RateLimitProperties.class})
public class AuthSupportConfig {

    /**
     * 创建账号密码编码器。
     *
     * @return 密码编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
