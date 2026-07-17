package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.RedSite;
import com.redculture.platform.mapper.RedSiteMapper;
import com.redculture.platform.service.RedSiteService;
import org.springframework.stereotype.Service;

@Service
public class RedSiteServiceImpl
        extends ServiceImpl<RedSiteMapper, RedSite>
        implements RedSiteService {
}
