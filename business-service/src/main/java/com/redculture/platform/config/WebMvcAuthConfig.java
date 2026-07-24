package com.redculture.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    private final AdminAccessInterceptor adminAccessInterceptor;
    private final AuthenticatedUserInterceptor authenticatedUserInterceptor;
    private final CsrfInterceptor csrfInterceptor;

    public WebMvcAuthConfig(AdminAccessInterceptor adminAccessInterceptor,
                            AuthenticatedUserInterceptor authenticatedUserInterceptor,
                            CsrfInterceptor csrfInterceptor) {
        this.adminAccessInterceptor = adminAccessInterceptor;
        this.authenticatedUserInterceptor = authenticatedUserInterceptor;
        this.csrfInterceptor = csrfInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticatedUserInterceptor)
                .addPathPatterns("/api/admin/**", "/api/map/**", "/api/school-map/**", "/api/ai/**",
                        "/api/auth/me", "/api/auth/profile", "/api/auth/password")
                .excludePathPatterns("/api/map/client-config");
        registry.addInterceptor(adminAccessInterceptor)
                .addPathPatterns("/api/admin/**");
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/api/**");
    }
}
