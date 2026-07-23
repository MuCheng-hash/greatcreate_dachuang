package com.redculture.platform.service.agent;

import com.redculture.platform.service.SchoolMapService;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.SchoolSummaryVO;
import com.redculture.platform.vo.ai.AgentToolRequest;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Agent 的统一范围和角色边界。LLM 只能拿到这里解析后的范围，不能自行生成 SQL/Cypher。
 */
@Component
public class AgentAccessGuard {

    private final SchoolMapService schoolMapService;

    public AgentAccessGuard(SchoolMapService schoolMapService) {
        this.schoolMapService = schoolMapService;
    }

    public ScopeResolution resolveScope(String scopeType,
                                        Long scopeId,
                                        AuthCurrentUserVO currentUser,
                                        String question) {
        if (currentUser == null) {
            throw new IllegalArgumentException("school account is required");
        }

        KnowledgeScopeType requestedType = KnowledgeScopeType.from(scopeType);
        boolean admin = "platform_admin".equals(currentUser.getRoleCode());

        if (!admin) {
            if (currentUser.getSchoolId() == null) {
                throw new IllegalArgumentException("school account is required");
            }
            if (requestedType != null && requestedType != KnowledgeScopeType.SCHOOL) {
                throw new IllegalArgumentException("school account can only query its own school");
            }
            if (scopeId != null && !scopeId.equals(currentUser.getSchoolId())) {
                throw new IllegalArgumentException("cannot access another school");
            }

            List<SchoolSummaryVO> mentionedSchools = findMentionedSchools(question);
            if (mentionedSchools.stream().anyMatch(school ->
                    !currentUser.getSchoolId().equals(school.getSchoolId()))) {
                throw new IllegalArgumentException("cannot access another school");
            }
            if (mentionedSchools.size() > 1) {
                return ScopeResolution.clarification(
                        "问题中匹配到多个学校，请补充完整学校名称。",
                        schoolNames(mentionedSchools)
                );
            }
            return ScopeResolution.resolved(KnowledgeScopeType.SCHOOL, currentUser.getSchoolId());
        }

        if (scopeId != null && requestedType == null) {
            requestedType = KnowledgeScopeType.SCHOOL;
        }
        if (scopeId != null) {
            return ScopeResolution.resolved(requestedType, scopeId);
        }
        if (requestedType != null) {
            return ScopeResolution.clarification("请补充当前范围的 scopeId。", Collections.emptyList());
        }

        List<SchoolSummaryVO> mentionedSchools = findMentionedSchools(question);
        if (mentionedSchools.size() == 1) {
            return ScopeResolution.resolved(KnowledgeScopeType.SCHOOL, mentionedSchools.get(0).getSchoolId());
        }
        if (mentionedSchools.size() > 1) {
            return ScopeResolution.clarification(
                    "问题中匹配到多个学校，请补充完整学校名称。",
                    schoolNames(mentionedSchools)
            );
        }
        return ScopeResolution.clarification("请补充具体学校名称，或传入学校 scopeId。", Collections.emptyList());
    }

    public void assertToolAccess(AgentToolRequest request) {
        if (request == null || request.getActor() == null || request.getScope() == null) {
            throw new IllegalArgumentException("agent actor and scope are required");
        }
        if (request.getActor().getAccountId() == null
                || !StringUtils.hasText(request.getActor().getRoleCode())) {
            throw new IllegalArgumentException("agent actor is invalid");
        }
        KnowledgeScopeType scopeType = KnowledgeScopeType.from(request.getScope().getScopeType());
        if (scopeType == null || request.getScope().getScopeId() == null || request.getScope().getScopeId() <= 0) {
            throw new IllegalArgumentException("agent scope is invalid");
        }

        if ("platform_admin".equals(request.getActor().getRoleCode())) {
            return;
        }
        if (!"school_admin".equals(request.getActor().getRoleCode())
                || scopeType != KnowledgeScopeType.SCHOOL
                || request.getActor().getSchoolId() == null
                || !request.getActor().getSchoolId().equals(request.getScope().getScopeId())) {
            throw new IllegalArgumentException("agent actor cannot access this scope");
        }
    }

    private List<SchoolSummaryVO> findMentionedSchools(String question) {
        if (!StringUtils.hasText(question)) {
            return Collections.emptyList();
        }
        List<SchoolSummaryVO> schools = schoolMapService.listSchools(null, null, null, 100);
        if (schools == null || schools.isEmpty()) {
            return Collections.emptyList();
        }
        String normalizedQuestion = normalizeForMatch(question);
        List<SchoolSummaryVO> matches = new ArrayList<>();
        for (SchoolSummaryVO school : schools) {
            if (school == null || school.getSchoolId() == null || !StringUtils.hasText(school.getSchoolName())) {
                continue;
            }
            List<String> aliases = schoolAliases(school.getSchoolName());
            if (aliases.stream().anyMatch(normalizedQuestion::contains)
                    && matches.stream().noneMatch(item -> school.getSchoolId().equals(item.getSchoolId()))) {
                matches.add(school);
            }
        }
        return matches;
    }

    /**
     * 返回官方名称和安全的末级学校名称别名。别名只从行政区划标记后派生，
     * 不做模糊相似度匹配，避免把用户问题自动绑定到错误学校。
     */
    private List<String> schoolAliases(String officialName) {
        String normalized = normalizeForMatch(officialName);
        List<String> aliases = new ArrayList<>();
        if (!StringUtils.hasText(normalized)) {
            return aliases;
        }
        aliases.add(normalized);

        String suffix = deriveSchoolSuffix(normalized);
        if (suffix.length() >= 4 && !aliases.contains(suffix)) {
            aliases.add(suffix);
        }
        return aliases;
    }

    private String deriveSchoolSuffix(String normalizedName) {
        int suffixStart = -1;
        for (String marker : List.of("街道", "省", "市", "区", "县", "镇", "乡", "村")) {
            int markerIndex = normalizedName.lastIndexOf(marker);
            if (markerIndex >= 0) {
                suffixStart = Math.max(suffixStart, markerIndex + marker.length());
            }
        }
        if (suffixStart <= 0 || suffixStart >= normalizedName.length()) {
            return "";
        }

        String suffix = normalizedName.substring(suffixStart);
        if (suffix.endsWith("小学")
                || suffix.endsWith("中学")
                || suffix.endsWith("学校")
                || suffix.endsWith("幼儿园")) {
            return suffix;
        }
        return "";
    }

    private List<String> schoolNames(List<SchoolSummaryVO> schools) {
        return schools.stream()
                .map(SchoolSummaryVO::getSchoolName)
                .filter(StringUtils::hasText)
                .toList();
    }

    private String normalizeForMatch(String value) {
        return value == null ? "" : value
                .replaceAll("\\s+", "")
                .replaceAll("[，。！？、；】【：‘’“”（）《》【】,.!?;:\"'()<>\\[\\]{}]", "")
                .toLowerCase(Locale.ROOT);
    }

    public record ScopeResolution(KnowledgeScopeType type,
                                  Long id,
                                  boolean clarificationRequired,
                                  String message,
                                  List<String> options) {

        public static ScopeResolution resolved(KnowledgeScopeType type, Long id) {
            return new ScopeResolution(type, id, false, null, Collections.emptyList());
        }

        public static ScopeResolution clarification(String message, List<String> options) {
            return new ScopeResolution(null, null, true, message,
                    options == null ? Collections.emptyList() : options);
        }
    }
}
