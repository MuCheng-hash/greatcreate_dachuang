package com.redculture.platform.service.admin;

import com.redculture.platform.vo.admin.RagWebSourceRequest;
import com.redculture.platform.vo.admin.RagWebSourceVO;

import java.util.List;

public interface RagWebSourceService {

    List<RagWebSourceVO> list();

    List<String> enabledDomains();

    RagWebSourceVO create(RagWebSourceRequest request);

    RagWebSourceVO update(Long sourceId, RagWebSourceRequest request);
}
