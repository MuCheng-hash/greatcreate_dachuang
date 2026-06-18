package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.SchoolUserAccount;
import com.redculture.platform.mapper.SchoolUserAccountMapper;
import com.redculture.platform.service.SchoolUserAccountService;
import org.springframework.stereotype.Service;

@Service
public class SchoolUserAccountServiceImpl extends ServiceImpl<SchoolUserAccountMapper, SchoolUserAccount>
        implements SchoolUserAccountService {
}
