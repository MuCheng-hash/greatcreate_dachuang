package com.redculture.platform.service.impl;

import com.redculture.platform.entity.LocalEduResource;
import com.redculture.platform.entity.ClassLearningTask;
import com.redculture.platform.entity.ClassMember;
import com.redculture.platform.entity.SchoolResourceRel;
import com.redculture.platform.entity.StudentProfile;
import com.redculture.platform.entity.StudentTaskProgress;
import com.redculture.platform.entity.TaskResourceRel;
import com.redculture.platform.enums.ReviewStatus;
import com.redculture.platform.mapper.ClassLearningTaskMapper;
import com.redculture.platform.mapper.ClassMemberMapper;
import com.redculture.platform.mapper.SchoolResourceRelMapper;
import com.redculture.platform.mapper.StudentProfileMapper;
import com.redculture.platform.mapper.StudentTaskProgressMapper;
import com.redculture.platform.mapper.TaskResourceRelMapper;
import com.redculture.platform.service.AgentToolService;
import com.redculture.platform.service.KnowledgeRetriever;
import com.redculture.platform.service.LocalEduResourceService;
import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.service.agent.AgentAccessGuard;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveRequest;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import com.redculture.platform.vo.ai.KnowledgeRetrievalStatus;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentToolServiceImpl implements AgentToolService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 8;

    private final AgentAccessGuard accessGuard;
    private final SchoolMapService schoolMapService;
    private final LocalEduResourceService localEduResourceService;
    private final KnowledgeRetriever knowledgeRetriever;

    @Autowired private StudentProfileMapper studentProfileMapper;
    @Autowired private StudentTaskProgressMapper studentTaskProgressMapper;
    @Autowired private ClassLearningTaskMapper classLearningTaskMapper;
    @Autowired private ClassMemberMapper classMemberMapper;
    @Autowired private TaskResourceRelMapper taskResourceRelMapper;
    @Autowired private SchoolResourceRelMapper schoolResourceRelMapper;

    public AgentToolServiceImpl(AgentAccessGuard accessGuard,
                                SchoolMapService schoolMapService,
                                LocalEduResourceService localEduResourceService,
                                KnowledgeRetriever knowledgeRetriever) {
        this.accessGuard = accessGuard;
        this.schoolMapService = schoolMapService;
        this.localEduResourceService = localEduResourceService;
        this.knowledgeRetriever = knowledgeRetriever;
    }

    @Override
    public Map<String, Object> schoolContext(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        KnowledgeScopeType scopeType = scopeType(request);
        if (scopeType != KnowledgeScopeType.SCHOOL) {
            throw new IllegalArgumentException("学校上下文只支持 SCHOOL 范围");
        }
        SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(request.getScope().getScopeId());
        if (detail == null) {
            throw new IllegalArgumentException("学校不存在或不可用");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("school", detail.getSchool());
        result.put("resources", detail.getResources());
        result.put("activityPlans", detail.getActivityPlans());
        result.put("resourceCount", detail.getResourceCount());
        result.put("activityPlanCount", detail.getActivityPlanCount());
        result.put("grade", request.getGrade());
        result.put("theme", request.getTheme());
        result.put("resourceCategory", request.getResourceCategory());
        result.put("maxDistanceMeters", request.getMaxDistanceMeters());
        return result;
    }

    @Override
    public LocalEduResource resourceDetail(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        if (request.getResourceId() == null || request.getResourceId() <= 0) {
            throw new IllegalArgumentException("resourceId 必须为正数");
        }
        LocalEduResource resource = localEduResourceService.getById(request.getResourceId());
        if (resource == null || !Boolean.TRUE.equals(resource.getActive())
                || resource.getReviewStatus() != ReviewStatus.APPROVED) {
            throw new IllegalArgumentException("资源不存在或不可用");
        }
        if (!"platform_admin".equals(request.getActor().getRoleCode())) {
            SchoolMapDetailVO detail = schoolMapService.getSchoolDetail(request.getScope().getScopeId());
            boolean related = detail != null && detail.getResources() != null
                    && detail.getResources().stream().anyMatch(item ->
                    item != null && request.getResourceId().equals(item.getResourceId()));
            if (!related) {
                throw new IllegalArgumentException("资源不在当前学校范围内");
            }
        }
        return resource;
    }

    @Override
    public KnowledgeRetrieveResult knowledgeRetrieve(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        return retrieve(request, request.getQuery(), null);
    }

    @Override
    public KnowledgeRetrieveResult relationQuery(AgentToolRequest request) {
        accessGuard.assertToolAccess(request);
        String query = StringUtils.hasText(request.getQuery()) ? request.getQuery().trim() : "关系查询";
        if (!query.contains("关系") && !query.contains("关联") && !query.contains("联系")) {
            query += " 关系";
        }
        return retrieve(request, query, AgentIntent.RELATION_QUERY);
    }

    private KnowledgeRetrieveResult retrieve(AgentToolRequest request,
                                             String query,
                                             AgentIntent intent) {
        KnowledgeRetrieveRequest retrieveRequest = new KnowledgeRetrieveRequest();
        retrieveRequest.setQuery(query);
        retrieveRequest.setIntent(intent == null ? request.getIntent() : intent.name());
        retrieveRequest.setScopeType(scopeType(request));
        retrieveRequest.setScopeId(request.getScope().getScopeId());
        retrieveRequest.setGrade(request.getGrade());
        retrieveRequest.setTheme(request.getTheme());
        retrieveRequest.setResourceCategory(request.getResourceCategory());
        retrieveRequest.setMaxDistanceMeters(request.getMaxDistanceMeters());
        List<Long> studentResourceIds = resolveStudentResourceIds(request);
        if (studentResourceIds != null) {
            retrieveRequest.setResourceIds(studentResourceIds);
        }
        retrieveRequest.setTopK(normalizeTopK(request.getTopK()));
        retrieveRequest.setHydeQuery(request.getHydeQuery());
        retrieveRequest.setWebEvidence(request.getWebEvidence());
        try {
            return normalize(knowledgeRetriever.retrieve(retrieveRequest));
        } catch (RuntimeException exception) {
            return KnowledgeRetrieveResult.degraded();
        }
    }

    private KnowledgeScopeType scopeType(AgentToolRequest request) {
        KnowledgeScopeType scopeType = KnowledgeScopeType.from(request.getScope().getScopeType());
        if (scopeType == null) {
            throw new IllegalArgumentException("scopeType 不能为空");
        }
        return scopeType;
    }

    /**
     * 学生约束必须在 Java 内部工具层重新计算，不能相信 FastAPI 转发的资源列表。
     * 返回 null 代表普通本校问答无需资源级收窄；空集合绝不被解释为无约束。
     */
    private List<Long> resolveStudentResourceIds(AgentToolRequest request) {
        if (!"student".equals(request.getActor().getRoleCode())) {
            return null;
        }
        if (scopeType(request) != KnowledgeScopeType.SCHOOL) {
            throw new IllegalArgumentException("学生账号只能查询本校数据");
        }
        Long schoolId = request.getScope().getScopeId();
        if (request.getResourceId() != null) {
            Long resourceId = request.getResourceId();
            LocalEduResource resource = localEduResourceService.getById(resourceId);
            boolean accessible = resource != null && Boolean.TRUE.equals(resource.getActive())
                    && resource.getReviewStatus() == ReviewStatus.APPROVED
                    && schoolResourceRelMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SchoolResourceRel>()
                    .eq(SchoolResourceRel::getSchoolId, schoolId)
                    .eq(SchoolResourceRel::getResourceId, resourceId));
            if (!accessible) {
                throw new IllegalArgumentException("该资源不对当前学生开放");
            }
            return List.of(resourceId);
        }
        if (request.getTaskId() == null) {
            return null;
        }
        StudentProfile student = studentProfileMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudentProfile>()
                .eq(StudentProfile::getAccountId, request.getActor().getAccountId())
                .eq(StudentProfile::getStatus, "active").last("LIMIT 1"));
        ClassLearningTask task = classLearningTaskMapper.selectById(request.getTaskId());
        boolean assigned = student != null && task != null && "published".equalsIgnoreCase(task.getStatus())
                && classMemberMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ClassMember>()
                .eq(ClassMember::getStudentId, student.getStudentId())
                .eq(ClassMember::getClassId, task.getClassId()).eq(ClassMember::getStatus, "active"))
                && studentTaskProgressMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudentTaskProgress>()
                .eq(StudentTaskProgress::getStudentId, student.getStudentId())
                .eq(StudentTaskProgress::getTaskId, task.getTaskId()));
        if (!assigned) {
            throw new IllegalArgumentException("该任务不对当前学生开放");
        }
        List<Long> resourceIds = taskResourceRelMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TaskResourceRel>()
                .eq(TaskResourceRel::getTaskId, task.getTaskId()).orderByAsc(TaskResourceRel::getSortOrder))
                .stream().map(TaskResourceRel::getResourceId).filter(java.util.Objects::nonNull).toList();
        if (resourceIds.isEmpty()) {
            throw new IllegalArgumentException("该任务未配置可检索资源");
        }
        for (Long resourceId : resourceIds) {
            LocalEduResource resource = localEduResourceService.getById(resourceId);
            boolean accessible = resource != null && Boolean.TRUE.equals(resource.getActive())
                    && resource.getReviewStatus() == ReviewStatus.APPROVED
                    && schoolResourceRelMapper.exists(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SchoolResourceRel>()
                    .eq(SchoolResourceRel::getSchoolId, schoolId)
                    .eq(SchoolResourceRel::getResourceId, resourceId));
            if (!accessible) {
                throw new IllegalArgumentException("任务包含当前学校不可访问的资源");
            }
        }
        return resourceIds;
    }

    private KnowledgeRetrieveResult normalize(KnowledgeRetrieveResult result) {
        if (result == null) {
            return KnowledgeRetrieveResult.degraded();
        }
        if (result.getChunks() == null) {
            result.setChunks(new ArrayList<>());
        }
        if (result.getGraphFacts() == null) {
            result.setGraphFacts(new ArrayList<>());
        }
        if (result.getCitationCandidates() == null) {
            result.setCitationCandidates(new ArrayList<>());
        }
        if (result.getRetrievalStatus() == null) {
            result.setRetrievalStatus(result.getChunks().isEmpty()
                    && result.getGraphFacts().isEmpty()
                    && result.getCitationCandidates().isEmpty()
                    ? KnowledgeRetrievalStatus.EMPTY
                    : KnowledgeRetrievalStatus.OK);
        }
        result.refreshRetrievalMethods();
        return result;
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(topK, MAX_TOP_K);
    }
}
