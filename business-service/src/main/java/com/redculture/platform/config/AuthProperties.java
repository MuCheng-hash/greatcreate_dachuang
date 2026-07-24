package com.redculture.platform.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** 开发环境默认值仅用于本地运行，生产环境必须通过 APP_AUTH_JWT_SECRET 覆盖。 */
    private String jwtSecret = "red-culture-development-jwt-secret-change-me";

    private long accessTokenTtlSeconds = 900L;

    private long refreshTokenTtlSeconds = 604800L;

    private boolean cookieSecure = false;

    private String cookieSameSite = "Lax";

    private String accessCookieName = "RC_ACCESS_TOKEN";

    private String refreshCookieName = "RC_REFRESH_TOKEN";

    private String csrfCookieName = "XSRF-TOKEN";

    private String csrfHeaderName = "X-CSRF-TOKEN";
}
