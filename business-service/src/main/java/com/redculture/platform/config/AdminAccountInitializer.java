package com.redculture.platform.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.service.SchoolUserAccountService;
import jakarta.annotation.PostConstruct;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AdminAccountInitializer {

    private final SchoolUserAccountService schoolUserAccountService;
    private final PasswordEncoder passwordEncoder;
    private final AdminAccountProperties properties;

    public AdminAccountInitializer(SchoolUserAccountService schoolUserAccountService,
                                   PasswordEncoder passwordEncoder,
                                   AdminAccountProperties properties) {
        this.schoolUserAccountService = schoolUserAccountService;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @PostConstruct
    public void ensureAdminAccount() {
        String username = StringUtils.hasText(properties.getUsername()) ? properties.getUsername().trim() : "admin";
        SchoolUserAccount account = schoolUserAccountService.getOne(new LambdaQueryWrapper<SchoolUserAccount>()
                .eq(SchoolUserAccount::getUsername, username)
                .last("LIMIT 1"));

        if (account == null) {
            account = new SchoolUserAccount();
            account.setUsername(username);
            account.setPasswordHash(passwordEncoder.encode(defaultPassword()));
        }

        if (!StringUtils.hasText(account.getPasswordHash())) {
            account.setPasswordHash(passwordEncoder.encode(defaultPassword()));
        }
        account.setRoleCode("platform_admin");
        account.setDisplayName(StringUtils.hasText(properties.getDisplayName()) ? properties.getDisplayName().trim() : "平台管理员");
        account.setStatus(AccountStatus.ACTIVE);
        account.setSchoolId(null);
        account.setRegistrationId(null);

        if (account.getAccountId() == null) {
            schoolUserAccountService.save(account);
        } else {
            schoolUserAccountService.updateById(account);
        }
    }

    private String defaultPassword() {
        return StringUtils.hasText(properties.getPassword()) ? properties.getPassword().trim() : "admin123456";
    }
}
