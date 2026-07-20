package com.redculture.platform.service.discovery;

import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.ResourceDiscoveryCandidate;
import com.redculture.platform.entity.School;
import com.redculture.platform.vo.discovery.DiscoveryClassificationResponse;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DiscoveryLlmClient {

    private final AppMapProperties properties;

    public DiscoveryLlmClient(AppMapProperties properties) {
        this.properties = properties;
    }

    public DiscoveryClassificationResponse classify(School school, List<ResourceDiscoveryCandidate> candidates) {
        if (!StringUtils.hasText(properties.getLlmServiceBaseUrl()) || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("school", Map.of(
                "schoolId", school.getSchoolId(),
                "schoolName", school.getSchoolName(),
                "address", school.getAddress() == null ? "" : school.getAddress(),
                "longitude", school.getLongitude(),
                "latitude", school.getLatitude()
        ));
        body.put("candidates", candidates.stream().map(candidate -> {
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
        }).toList());
        try {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout(25_000);
            return RestClient.builder().baseUrl(properties.getLlmServiceBaseUrl()).requestFactory(factory).build().post()
                    .uri("/llm/resource-discovery/classify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(DiscoveryClassificationResponse.class);
        } catch (Exception exception) {
            return null;
        }
    }
}
