package com.redculture.platform;

import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.config.RagProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AppMapProperties.class, AgentProperties.class, RagProperties.class})
public class RedCulturePlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(RedCulturePlatformApplication.class, args);
    }
}
