package com.redculture.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 统一注册认证、CSRF、限流、角色拦截器及媒体资源映射。
 */
@Configuration
public class WebMvcAuthConfig implements WebMvcConfigurer {

    /**
     * 后台管理访问拦截器。
     */
    private final AdminAccessInterceptor adminAccessInterceptor;
    /**
     * 登录用户认证拦截器。
     */
    private final AuthenticatedUserInterceptor authenticatedUserInterceptor;
    /**
     * CSRF 防护拦截器。
     */
    private final CsrfInterceptor csrfInterceptor;
    /**
     * 请求限流拦截器。
     */
    private final RequestRateLimitInterceptor rateLimitInterceptor;
    /**
     * 角色权限拦截器。
     */
    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor;
    /**
     * 后台媒体存储配置。
     */
    private final AdminMediaProperties adminMediaProperties;

    /**
     * 创建 Web MVC 认证与资源映射配置。
     *
     * @param adminAccessInterceptor 后台管理访问拦截器
     * @param authenticatedUserInterceptor 登录用户认证拦截器
     * @param csrfInterceptor CSRF 拦截器
     * @param rateLimitInterceptor 限流拦截器
     * @param roleAuthorizationInterceptor 角色授权拦截器
     * @param adminMediaProperties 后台媒体存储配置
     */
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

    /**
     * 按接口范围注册认证、授权、CSRF 和限流拦截器。
     *
     * @param registry MVC 注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticatedUserInterceptor)
                .addPathPatterns("/api/admin/**", "/api/teacher/**", "/api/student/**", "/api/map/**", "/api/school-map/**", "/api/ai/**",
                        "/api/auth/me", "/api/auth/profile", "/api/auth/password")
                .excludePathPatterns("/api/map/client-config");
        registry.addInterceptor(roleAuthorizationInterceptor)
                .addPathPatterns("/api/admin/**", "/api/teacher/**", "/api/student/**", "/api/map/**", "/api/school-map/**", "/api/ai/**",
                        "/api/auth/me", "/api/auth/profile", "/api/auth/password")
                .excludePathPatterns("/api/map/client-config");
        registry.addInterceptor(adminAccessInterceptor)
                .addPathPatterns("/api/admin/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/auth/login", "/api/ai/**");
        registry.addInterceptor(csrfInterceptor)
                .addPathPatterns("/api/**");
    }

    /**
     * 注册后台媒体文件的静态资源映射。
     *
     * @param registry MVC 注册表
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/resource-media/**")
                .addResourceLocations(adminMediaProperties.storagePath().toUri().toString());
    }
}
