package com.redculture.platform.service.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.redculture.platform.config.AppMapProperties;
import com.redculture.platform.entity.School;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AmapPoiClient {

    private static final List<String> KEYWORD_GROUPS = List.of(
            "烈士陵园|纪念馆|革命旧址|爱国主义教育基地|博物馆|村史馆",
            "文化馆|图书馆|非遗馆|美术馆|传统村落|历史建筑",
            "劳动教育基地|农业基地|科普基地|青少年活动中心",
            "敬老院|福利院|志愿服务站|湿地公园|自然保护区|生态园"
    );

    private final AppMapProperties properties;

    public AmapPoiClient(AppMapProperties properties) {
        this.properties = properties;
    }

    public List<PoiRecord> searchNearby(School school, int radiusMeters) {
        validateConfiguration(school);
        RestClient client = client();
        Map<String, PoiRecord> unique = new LinkedHashMap<>();
        for (String keywords : KEYWORD_GROUPS) {
            JsonNode response = client.get()
                    .uri(builder -> builder.path("/v3/place/around")
                            .queryParam("key", properties.getAmapWebServiceKey())
                            .queryParam("location", school.getLongitude() + "," + school.getLatitude())
                            .queryParam("keywords", keywords)
                            .queryParam("radius", radiusMeters)
                            .queryParam("sortrule", "distance")
                            .queryParam("offset", 20)
                            .queryParam("page", 1)
                            .queryParam("extensions", "all")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            parseResponse(response).forEach(item -> unique.putIfAbsent(item.providerPlaceId(), item));
        }
        int limit = Math.max(1, properties.getDiscoveryMaxCandidates());
        return unique.values().stream()
                .sorted(Comparator.comparing(PoiRecord::distanceMeters,
                        Comparator.nullsLast(Integer::compareTo)))
                .limit(limit)
                .toList();
    }

    public PoiRecord getDetail(String providerPlaceId) {
        if (!StringUtils.hasText(properties.getAmapWebServiceKey()) || !StringUtils.hasText(providerPlaceId)) {
            return null;
        }
        JsonNode response = client().get()
                .uri(builder -> builder.path("/v3/place/detail")
                        .queryParam("key", properties.getAmapWebServiceKey())
                        .queryParam("id", providerPlaceId.trim())
                        .queryParam("extensions", "all")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        List<PoiRecord> records = parseResponse(response);
        return records.isEmpty() ? null : records.getFirst();
    }

    private void validateConfiguration(School school) {
        if (!StringUtils.hasText(properties.getAmapWebServiceKey())) {
            throw new IllegalStateException("AMAP_WEB_SERVICE_KEY is not configured");
        }
        if (school == null || school.getLongitude() == null || school.getLatitude() == null) {
            throw new IllegalArgumentException("school coordinates are required");
        }
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return RestClient.builder()
                .baseUrl(properties.getAmapWebServiceBaseUrl())
                .requestFactory(factory)
                .build();
    }

    private List<PoiRecord> parseResponse(JsonNode response) {
        if (response == null || !"1".equals(response.path("status").asText())) {
            String message = response == null ? "empty response" : response.path("info").asText("provider error");
            throw new IllegalStateException("AMap request failed: " + message);
        }
        List<PoiRecord> records = new ArrayList<>();
        JsonNode pois = response.path("pois");
        if (!pois.isArray()) {
            return records;
        }
        for (JsonNode poi : pois) {
            String id = text(poi.get("id"));
            String name = text(poi.get("name"));
            BigDecimal[] location = location(poi.get("location"));
            if (!StringUtils.hasText(id) || !StringUtils.hasText(name) || location == null) {
                continue;
            }
            records.add(new PoiRecord(
                    id,
                    name,
                    text(poi.get("address")),
                    location[0],
                    location[1],
                    text(poi.get("typecode")),
                    text(poi.get("type")),
                    text(poi.get("tel")),
                    firstText(poi.path("biz_ext").get("open_time"), poi.get("opentime_today")),
                    integer(poi.get("distance")),
                    poi.toString()
            ));
        }
        return records;
    }

    private BigDecimal[] location(JsonNode node) {
        String value = text(node);
        if (!StringUtils.hasText(value) || !value.contains(",")) {
            return null;
        }
        try {
            String[] parts = value.split(",", 2);
            return new BigDecimal[]{new BigDecimal(parts[0]), new BigDecimal(parts[1])};
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer integer(JsonNode node) {
        String value = text(node);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = text(node);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String text(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(item -> {
                if (item.isValueNode() && StringUtils.hasText(item.asText())) {
                    values.add(item.asText());
                }
            });
            return values.isEmpty() ? null : String.join(", ", values);
        }
        String value = node.asText();
        return StringUtils.hasText(value) && !"[]".equals(value) ? value.trim() : null;
    }

    public record PoiRecord(String providerPlaceId,
                            String placeName,
                            String address,
                            BigDecimal longitude,
                            BigDecimal latitude,
                            String providerTypeCode,
                            String providerTypeName,
                            String contactPhone,
                            String openingHours,
                            Integer distanceMeters,
                            String rawJson) {
    }
}
