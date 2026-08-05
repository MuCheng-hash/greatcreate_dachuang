package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.ContentChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Mapper
public interface ContentChunkMapper extends BaseMapper<ContentChunk> {

    @Select("""
            <script>
            SELECT chunk_id, entity_type, entity_id, chunk_title, chunk_text, retrieval_text,
                   chunk_index, source_id, token_count, embedding_status, embedding_hash,
                   embedding_model, embedding_dimensions, embedding_index_version, embedded_at,
                   created_at, updated_at
            FROM content_chunk
            WHERE (
                <foreach collection="entityIdsByType" item="ids" index="entityType" separator=" OR ">
                    (entity_type = #{entityType}
                     AND entity_id IN
                     <foreach collection="ids" item="entityId" open="(" separator="," close=")">
                         #{entityId}
                     </foreach>)
                </foreach>
            )
            AND MATCH(chunk_title, chunk_text, retrieval_text)
                AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY MATCH(chunk_title, chunk_text, retrieval_text)
                AGAINST(#{query} IN NATURAL LANGUAGE MODE) DESC,
                chunk_id ASC
            LIMIT #{limit}
            </script>
            """)
    List<ContentChunk> searchByFullText(
            @Param("entityIdsByType") Map<String, Collection<Long>> entityIdsByType,
            @Param("query") String query,
            @Param("limit") int limit
    );
}
