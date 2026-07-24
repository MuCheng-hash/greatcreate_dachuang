package com.redculture.platform.service.discovery;

import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.ResourceDiscoveryCandidate;
import com.redculture.platform.entity.School;
import com.redculture.platform.service.agent.AgentRuntimeClient;
import com.redculture.platform.vo.discovery.DiscoveryClassificationResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DiscoveryLlmClient {

    private final AppMapProperties properties;
    private final AgentRuntimeClient agentRuntimeClient;

    public DiscoveryLlmClient(AppMapProperties properties, AgentRuntimeClient agentRuntimeClient) {
        this.properties = properties;
        this.agentRuntimeClient = agentRuntimeClient;
    }

    public DiscoveryClassificationResponse classify(School school, List<ResourceDiscoveryCandidate> candidates) {
        if (!StringUtils.hasText(properties.getLlmServiceBaseUrl()) || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Map<String, Object> schoolPayload = new LinkedHashMap<>();
        schoolPayload.put("schoolId", school.getSchoolId());
        schoolPayload.put("schoolName", school.getSchoolName());
        schoolPayload.put("address", school.getAddress() == null ? "" : school.getAddress());
        schoolPayload.put("longitude", school.getLongitude());
        schoolPayload.put("latitude", school.getLatitude());
        List<Map<String, Object>> candidatePayload = candidates.stream().map(candidate -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("providerPlaceId", candidate.getProviderPlaceId());
            item.put("name", candidate.getPlaceName());
            item.put("address", candidate.getAddress());
            item.put("longitude", candidate.getLongitude());
            item.put("latitude", candidate.getLatitude());
            item.put("typeCode", candidate.getProviderTypeCode());
            item.put("typeName", candidate.getProviderTypeName());
            item.put("distanceMeters", candidate.getDistanceMeters());
            return item;
        }).toList();
        Map<String, Object> taskPayload = new LinkedHashMap<>();
        taskPayload.put("candidates", candidatePayload);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("school", schoolPayload);
        context.put("resources", new ArrayList<>());
        context.put("citationCandidates", new ArrayList<>());
        String ownerId = "service:resource-discovery:" + school.getSchoolId();
        String threadId = null;
        try {
            var request = agentRuntimeClient.taskRequest(
                    ownerId,
                    "SCHOOL",
                    school.getSchoolId(),
                    null,
                    "RESOURCE_DISCOVERY",
                    "请分析候选地点是否具有思政教育价值。",
                    taskPayload,
                    context
            );
            var response = agentRuntimeClient.send(request);
            threadId = response.getThreadId();
            return response.getResourceDiscovery();
        } catch (Exception exception) {
            return null;
        } finally {
            if (StringUtils.hasText(threadId)) {
                agentRuntimeClient.archive(threadId, ownerId);
            }
        }
    }
}
