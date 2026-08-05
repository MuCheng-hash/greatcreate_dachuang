package com.redculture.platform.service.rag;

import com.redculture.platform.enums.EntityType;

import java.util.List;

public record RagEntityMetadata(EntityType entityType,
                                Long entityId,
                                String canonicalName,
                                List<String> aliases,
                                String regionName,
                                String entityCategory,
                                String grade,
                                String theme,
                                Long sourceId,
                                String relatedEntities,
                                String retrievalText) {

    public String entityKey() {
        return entityType.getValue() + ":" + entityId;
    }
}
