package com.redculture.platform.service.agent;

import com.redculture.platform.vo.AgentIntent;
import com.redculture.platform.vo.LocalEduResourceSummaryVO;
import com.redculture.platform.vo.SchoolMapDetailVO;
import com.redculture.platform.vo.SchoolResourceItemVO;
import com.redculture.platform.vo.TeachingActivityPlanVO;
import com.redculture.platform.vo.TownMapDetailVO;
import com.redculture.platform.vo.ai.KnowledgeGraphFactVO;
import com.redculture.platform.vo.ai.KnowledgeRetrieveResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@ConditionalOnMissingBean(AnswerGenerator.class)
public class TemplateAnswerGenerator implements AnswerGenerator {

    @Override
    public GeneratedAnswer generate(AgentAnswerContext context) {
        KnowledgeRetrieveResult retrieval = context.getRetrieval();
        List<String> citationIds = retrieval == null
                ? new ArrayList<>()
                : new ArrayList<>(retrieval.allCitationIds());
        List<String> followUps = followUps(context.getIntent());
        String answer = switch (context.getIntent()) {
            case NEARBY_RESOURCE -> nearbyAnswer(context);
            case TEACHING_SUGGESTION -> teachingAnswer(context);
            case RESOURCE_EXPLANATION -> resourceAnswer(context);
            case RELATION_QUERY -> relationAnswer(context);
            case UNKNOWN -> unknownAnswer();
        };
        if (retrieval != null && retrieval.getRetrievalStatus() != null
                && retrieval.getRetrievalStatus().getValue().equals("degraded")) {
            answer += "\n\n提示：部分知识检索能力暂不可用，以上内容仅基于当前业务数据和可用证据。";
        }
        return new GeneratedAnswer(answer, citationIds, followUps);
    }

    private String nearbyAnswer(AgentAnswerContext context) {
        SchoolMapDetailVO detail = context.getSchoolDetail();
        if (detail == null || detail.getResources() == null || detail.getResources().isEmpty()) {
            return "当前范围内暂未查询到已审核的本土教育资源。你可以换一个区域或补充具体主题继续查询。";
        }
        String schoolName = detail.getSchool() == null ? "当前学校" : detail.getSchool().getSchoolName();
        String resources = detail.getResources().stream()
                .map(this::resourceDescription)
                .limit(5)
                .collect(Collectors.joining("；"));
        return schoolName + "当前关联的周边教育资源包括：" + resources + "。可以结合年级和主题进一步筛选。";
    }

    private String teachingAnswer(AgentAnswerContext context) {
        SchoolMapDetailVO detail = context.getSchoolDetail();
        if (detail != null && detail.getActivityPlans() != null && !detail.getActivityPlans().isEmpty()) {
            TeachingActivityPlanVO plan = detail.getActivityPlans().get(0);
            String objective = textOrDefault(plan.getObjectiveText(), "认识本地文化并形成实践体验");
            String activity = textOrDefault(plan.getActivityContent(), "课堂导入、现场观察、实践反思");
            return "可以参考已有活动方案《" + textOrDefault(plan.getTheme(), "本地文化实践")
                    + "》：目标是" + objective + "；活动内容可安排为" + activity + "。";
        }
        String resourceName = firstResourceName(detail);
        if (resourceName == null && context.getResource() != null) {
            resourceName = context.getResource().getResourceName();
        }
        if (resourceName == null) {
            resourceName = "本地红色文化资源";
        }
        return "可以围绕“" + resourceName + "”设计课堂导入、现场观察、志愿实践和课后反思四个环节，并根据"
                + textOrDefault(context.getGrade(), "学生年级") + "的理解能力调整任务难度。";
    }

    private String resourceAnswer(AgentAnswerContext context) {
        if (context.getResource() != null) {
            return textOrDefault(context.getResource().getResourceName(), "该教育资源") + "："
                    + textOrDefault(context.getResource().getIntro(), "暂无详细介绍") + "。教育价值："
                    + textOrDefault(context.getResource().getEducationValue(), "可结合本地文化开展实践教育");
        }
        LocalEduResourceSummaryVO resource = context.getMatchedSchoolResource();
        if (resource != null) {
            return textOrDefault(resource.getResourceName(), "该教育资源") + "："
                    + textOrDefault(resource.getIntro(), "暂无详细介绍") + "。教育价值："
                    + textOrDefault(resource.getEducationValue(), "可结合本地文化开展实践教育");
        }
        return "请补充具体的资源名称或资源 ID，我可以继续介绍它的教育价值、适用年级和活动方向。";
    }

    private String relationAnswer(AgentAnswerContext context) {
        KnowledgeRetrieveResult retrieval = context.getRetrieval();
        List<KnowledgeGraphFactVO> facts = retrieval == null ? List.of() : retrieval.getGraphFacts();
        if (facts != null && !facts.isEmpty()) {
            return "查询到以下关联事实：" + facts.stream()
                    .map(KnowledgeGraphFactVO::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("；"));
        }
        TownMapDetailVO region = context.getRegionDetail();
        if (region != null && Boolean.TRUE.equals(region.getGraphAvailable())) {
            return "当前区域图谱已连接，但没有返回与问题直接匹配的关系事实。请补充具体人物、学校或资源名称。";
        }
        return "当前没有查询到明确的图谱关系事实。请补充具体的人物、学校或资源名称后再试。";
    }

    private String unknownAnswer() {
        return "我目前支持查询周边资源、解释教育资源、设计教学活动，以及查询人物、学校和资源之间的关系。请补充学校、资源或区域名称。";
    }

    private List<String> followUps(AgentIntent intent) {
        return switch (intent) {
            case NEARBY_RESOURCE -> List.of("这些资源适合哪个年级？", "请给出一个校外实践活动方案。");
            case TEACHING_SUGGESTION -> List.of("请补充安全注意事项。", "这个活动适合多长时间？");
            case RESOURCE_EXPLANATION -> List.of("这个资源适合设计成什么样的思政课？", "它适合哪个年级？");
            case RELATION_QUERY -> List.of("请介绍这个资源的教育价值。", "附近还有哪些相关资源？");
            case UNKNOWN -> List.of("查询学校附近的红色资源。", "介绍一个资源的教育价值。");
        };
    }

    private String resourceDescription(SchoolResourceItemVO item) {
        if (item == null || item.getResource() == null) {
            return "未命名资源";
        }
        String name = textOrDefault(item.getResource().getResourceName(), "未命名资源");
        if (item.getDistanceMeters() == null) {
            return name;
        }
        return name + "（约" + item.getDistanceMeters() + "米）";
    }

    private String firstResourceName(SchoolMapDetailVO detail) {
        if (detail == null || detail.getResources() == null) {
            return null;
        }
        return detail.getResources().stream()
                .map(SchoolResourceItemVO::getResource)
                .filter(resource -> resource != null && resource.getResourceName() != null)
                .map(LocalEduResourceSummaryVO::getResourceName)
                .findFirst()
                .orElse(null);
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
