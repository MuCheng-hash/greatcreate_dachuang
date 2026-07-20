package com.redculture.platform.service.agent;

import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.TownMapDetailVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import com.redculture.platform.entity.LocalEduResource;
import lombok.Data;

@Data
public class AgentAnswerContext {

    private String question;

    private AgentIntent intent;

    private KnowledgeScopeType scopeType;

    private Long scopeId;

    private String grade;

    private String theme;

    private SchoolMapDetailVO schoolDetail;

    private TownMapDetailVO regionDetail;

    private LocalEduResource resource;

    private LocalEduResourceSummaryVO matchedSchoolResource;

    private KnowledgeRetrieveResult retrieval;
}
