package com.redculture.platform.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.neo4j.core.Neo4jClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedCultureGraphMapServiceImplTest {

    @Test
    void returnsEmptyMarkersWhenNeo4jIsUnavailable() {
        Neo4jClient neo4jClient = mock(Neo4jClient.class);
        when(neo4jClient.query(anyString()))
                .thenThrow(new TransientDataAccessResourceException("Neo4j is unavailable"));

        RedCultureGraphMapServiceImpl service = new RedCultureGraphMapServiceImpl(neo4jClient);

        assertThat(service.listPublishedSites(null)).isEmpty();
    }
}
