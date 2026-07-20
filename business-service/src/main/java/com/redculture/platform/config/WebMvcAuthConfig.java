package com.redculture.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    private final AdminAccessInterceptor adminAccessInterceptor;
    private final AuthenticatedUserInterceptor authenticatedUserInterceptor;

    public WebMvcAuthConfig(AdminAccessInterceptor adminAccessInterceptor,
                            AuthenticatedUserInterceptor authenticatedUserInterceptor) {
        this.adminAccessInterceptor = adminAccessInterceptor;
        this.authenticatedUserInterceptor = authenticatedUserInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAccessInterceptor)
                .addPathPatterns("/api/admin/**");
        registry.addInterceptor(authenticatedUserInterceptor)
                .addPathPatterns("/api/map/**", "/api/school-map/**", "/api/ai/**",
                        "/api/auth/profile", "/api/auth/password")
                .excludePathPatterns("/api/map/client-config");
    }
}
