package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.HeroPerson;
import com.redculture.platform.mapper.HeroPersonMapper;
import com.redculture.platform.service.HeroPersonService;
import org.springframework.stereotype.Service;

@Service
public class HeroPersonServiceImpl
        extends ServiceImpl<HeroPersonMapper, HeroPerson>
        implements HeroPersonService {
}
