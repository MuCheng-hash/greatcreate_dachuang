package com.redculture.platform.service.agent;

import com.redculture.platform.config.AgentProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import com.redculture.platform.vo.ai.KnowledgeScopeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolContextAuthorizationTest {

    @Test
    void rejectsTamperedExpiredOrDisallowedToolAuthorization() {
        AgentProperties properties = new AgentProperties();
        properties.setToolContextSigningSecret("unit-test-secret");
        AgentToolContextAuthorization authorization = new AgentToolContextAuthorization(properties);
        AuthCurrentUserVO user = new AuthCurrentUserVO();
        user.setAccountId(1L);
        user.setRoleCode("student");
        user.setSchoolId(7L);

        String token = authorization.issue(new AgentToolContextAuthorization.Context(
                user, KnowledgeScopeType.SCHOOL, 7L, "turn-1",
                List.of("retrieve_knowledge"), 31L, null
        ), Instant.parse("2026-09-20T00:00:00Z"));

        AgentToolContextAuthorization.VerifiedContext verified = authorization.verify(
                token, "retrieve_knowledge", Instant.parse("2026-09-20T00:01:00Z")
        );
        assertEquals(1L, verified.accountId());
        assertEquals(31L, verified.taskId());
        assertThrows(IllegalArgumentException.class, () -> authorization.verify(
                token, "query_graph_relations", Instant.parse("2026-09-20T00:01:00Z")
        ));
        assertThrows(IllegalArgumentException.class, () -> authorization.verify(
                token + "x", "retrieve_knowledge", Instant.parse("2026-09-20T00:01:00Z")
        ));
        assertThrows(IllegalArgumentException.class, () -> authorization.verify(
                token, "retrieve_knowledge", Instant.parse("2026-09-20T00:03:00Z")
        ));
    }
}
