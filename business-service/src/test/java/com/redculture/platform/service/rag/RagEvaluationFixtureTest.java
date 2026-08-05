package com.redculture.platform.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationFixtureTest {

    @Test
    void evaluationBaselineHasRequiredDistributionAndThresholds() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/rag-evaluation-v2.json")) {
            assertNotNull(input);
            JsonNode root = new ObjectMapper().readTree(input);
            assertEquals(24, root.path("cases").size());
            Map<String, Integer> counts = new LinkedHashMap<>();
            root.path("cases").forEach(item -> counts.merge(item.path("category").asText(), 1, Integer::sum));
            assertEquals(Map.of("nearby", 6, "teaching", 5, "relation", 5,
                    "explanation", 4, "negative", 4), counts);
            assertTrue(root.path("thresholds").path("recallAt8").asDouble() >= 0.85D);
            assertTrue(root.path("thresholds").path("mrrAt8").asDouble() >= 0.70D);
            assertTrue(root.path("thresholds").path("graphPredicateAccuracy").asDouble() >= 0.80D);
        }
    }
}
