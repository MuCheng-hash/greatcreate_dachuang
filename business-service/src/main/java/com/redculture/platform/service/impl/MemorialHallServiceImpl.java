package com.redculture.platform.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.redculture.platform.entity.MemorialHall;
import com.redculture.platform.mapper.MemorialHallMapper;
import com.redculture.platform.service.MemorialHallService;
import org.springframework.stereotype.Service;

@Service
public class MemorialHallServiceImpl
        extends ServiceImpl<MemorialHallMapper, MemorialHall>
        implements MemorialHallService {
}
