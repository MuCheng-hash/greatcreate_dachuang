package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.enums.AccountStatus;
import com.redculture.platform.mapper.SchoolUserAccountMapper;
import com.redculture.platform.service.SchoolUserAccountService;
import com.redculture.platform.service.auth.AuthTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SchoolUserAccountServiceImpl extends ServiceImpl<SchoolUserAccountMapper, SchoolUserAccount>
        implements SchoolUserAccountService {

    private final AuthTokenService authTokenService;

    @Autowired
    public SchoolUserAccountServiceImpl(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @Override
    public boolean updateById(SchoolUserAccount entity) {
        boolean updated = super.updateById(entity);
        if (updated && entity != null && entity.getStatus() == AccountStatus.DISABLED
                && entity.getAccountId() != null) {
            authTokenService.revokeAll(entity.getAccountId(), "account_disabled");
        }
        return updated;
    }
}
