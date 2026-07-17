package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.AdministrativeRegion;
import com.redculture.platform.mapper.AdministrativeRegionMapper;
import com.redculture.platform.service.AdministrativeRegionService;
import org.springframework.stereotype.Service;

@Service
public class AdministrativeRegionServiceImpl
        extends ServiceImpl<AdministrativeRegionMapper, AdministrativeRegion>
        implements AdministrativeRegionService {
}
