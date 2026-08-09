package com.redculture.platform.service.admin.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.redculture.platform.entity.RagWebSource;
import com.redculture.platform.mapper.RagWebSourceMapper;
import com.redculture.platform.service.admin.RagWebSourceService;
import com.redculture.platform.vo.admin.RagWebSourceRequest;
import com.redculture.platform.vo.admin.RagWebSourceVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class RagWebSourceServiceImpl implements RagWebSourceService {

    private final RagWebSourceMapper mapper;

    public RagWebSourceServiceImpl(RagWebSourceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<RagWebSourceVO> list() {
        return mapper.selectList(new LambdaQueryWrapper<RagWebSource>()
                        .orderByAsc(RagWebSource::getSortOrder)
                        .orderByAsc(RagWebSource::getSourceId))
                .stream().map(this::toVo).toList();
    }

    @Override
    public List<String> enabledDomains() {
        return mapper.selectList(new LambdaQueryWrapper<RagWebSource>()
                        .eq(RagWebSource::getEnabled, true)
                        .orderByAsc(RagWebSource::getSortOrder)
                        .orderByAsc(RagWebSource::getSourceId))
                .stream().map(RagWebSource::getDomain).filter(StringUtils::hasText).toList();
    }

    @Override
    public RagWebSourceVO create(RagWebSourceRequest request) {
        RagWebSource source = new RagWebSource();
        apply(source, request);
        source.setCreatedAt(LocalDateTime.now());
        source.setUpdatedAt(LocalDateTime.now());
        mapper.insert(source);
        return toVo(source);
    }

    @Override
    public RagWebSourceVO update(Long sourceId, RagWebSourceRequest request) {
        RagWebSource source = mapper.selectById(sourceId);
        if (source == null) {
            throw new IllegalArgumentException("web source not found");
        }
        apply(source, request);
        source.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(source);
        return toVo(source);
    }

    private void apply(RagWebSource source, RagWebSourceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("web source request is required");
        }
        String domain = normalizeDomain(request.getDomain());
        Long duplicate = mapper.selectCount(new LambdaQueryWrapper<RagWebSource>()
                .eq(RagWebSource::getDomain, domain)
                .ne(source.getSourceId() != null, RagWebSource::getSourceId, source.getSourceId()));
        if (duplicate != null && duplicate > 0) {
            throw new IllegalArgumentException("web source domain already exists");
        }
        source.setDomain(domain);
        source.setDisplayName(StringUtils.hasText(request.getDisplayName()) ? request.getDisplayName().trim() : domain);
        source.setEnabled(request.getEnabled() == null || request.getEnabled());
        source.setSortOrder(request.getSortOrder() == null ? 100 : Math.max(0, request.getSortOrder()));
    }

    public static String normalizeDomain(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("web source domain is required");
        }
        String candidate = value.trim().toLowerCase();
        if (!candidate.contains("://")) {
            candidate = "https://" + candidate;
        }
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !StringUtils.hasText(host)
                    || host.contains("..") || host.contains("/")) {
                throw new IllegalArgumentException("web source must be a valid HTTPS domain");
            }
            return host.toLowerCase();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("web source must be a valid HTTPS domain");
        }
    }

    private RagWebSourceVO toVo(RagWebSource source) {
        RagWebSourceVO result = new RagWebSourceVO();
        result.setSourceId(source.getSourceId());
        result.setDisplayName(source.getDisplayName());
        result.setDomain(source.getDomain());
        result.setEnabled(source.getEnabled());
        result.setSortOrder(source.getSortOrder());
        result.setCreatedAt(source.getCreatedAt());
        result.setUpdatedAt(source.getUpdatedAt());
        return result;
    }
}
