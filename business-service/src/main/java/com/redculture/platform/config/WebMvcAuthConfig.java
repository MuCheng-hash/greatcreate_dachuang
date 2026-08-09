package com.redculture.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    private final AdminAccessInterceptor adminAccessInterceptor;
    private final AuthenticatedUserInterceptor authenticatedUserInterceptor;
    private final CsrfInterceptor csrfInterceptor;
    private final RequestRateLimitInterceptor rateLimitInterceptor;
    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor;
    private final AdminMediaProperties adminMediaProperties;

    public WebMvcAuthConfig(AdminAccessInterceptor adminAccessInterceptor,
                            AuthenticatedUserInterceptor authenticatedUserInterceptor,
                            CsrfInterceptor csrfInterceptor,
                            RequestRateLimitInterceptor rateLimitInterceptor,
                            RoleAuthorizationInterceptor roleAuthorizationInterceptor,
                            AdminMediaProperties adminMediaProperties) {
        this.adminAccessInterceptor = adminAccessInterceptor;
        this.authenticatedUserInterceptor = authenticatedUserInterceptor;
        this.csrfInterceptor = csrfInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.roleAuthorizationInterceptor = roleAuthorizationInterceptor;
        this.adminMediaProperties = adminMediaProperties;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticatedUserInterceptor)
                .addPathPatterns("/api/admin/**", "/api/map/**", "/api/school-map/**", "/api/ai/**",
                        "/api/knowledge-documents/**", "/api/auth/me", "/api/auth/profile", "/api/auth/password")
                .excludePathPatterns("/api/map/client-config");
        registry.addInterceptor(roleAuthorizationInterceptor)
                .addPathPatterns("/api/admin/**", "/api/map/**", "/api/school-map/**", "/api/ai/**",
                        "/api/knowledge-documents/**", "/api/auth/me", "/api/auth/profile", "/api/auth/password")
                .excludePathPatterns("/api/map/client-config");
        registry.addInterceptor(adminAccessInterceptor)
                .addPathPatterns("/api/admin/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/auth/login", "/api/ai/**");
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/api/**");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/resource-media/**")
                .addResourceLocations(adminMediaProperties.storagePath().toUri().toString());
    }
}
