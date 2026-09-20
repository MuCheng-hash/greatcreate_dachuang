package com.redculture.platform.service;

import com.redculture.platform.config.KnowledgeStorageProperties;
import com.redculture.platform.entity.KnowledgeDocument;
import com.redculture.platform.entity.KnowledgeIngestJob;
import com.redculture.platform.mapper.KnowledgeDocumentMapper;
import com.redculture.platform.mapper.KnowledgeIngestJobMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeDocumentServiceTest {

    @Test
    void listsSelectedSchoolDocumentsWithTheirIngestionStatus() {
        KnowledgeDocumentMapper documents = mock(KnowledgeDocumentMapper.class);
        KnowledgeIngestJobMapper jobs = mock(KnowledgeIngestJobMapper.class);
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(31L);
        document.setSchoolId(7L);
        document.setTitle("红色文化活动方案");
        document.setOriginalFilename("activity.md");
        document.setFileSize(2048L);
        document.setStatus("FAILED");
        KnowledgeIngestJob job = new KnowledgeIngestJob();
        job.setDocumentId(31L);
        job.setStatus("FAILED");
        job.setCurrentNode("INDEX");
        job.setRetryCount(2);
        job.setErrorSummary("EmbeddingUnavailable");
        job.setFinishedAt(LocalDateTime.of(2026, 9, 20, 9, 30));
        when(documents.selectList(any())).thenReturn(List.of(document));
        when(jobs.selectList(any())).thenReturn(List.of(job));

        KnowledgeDocumentService service = new KnowledgeDocumentService(documents, jobs,
                mock(SchoolAccessService.class), mock(io.minio.MinioClient.class),
                new KnowledgeStorageProperties(), mock(KnowledgeIngestTransactions.class),
                mock(KnowledgeVectorCleanupService.class));

        var result = service.listForAdmin("school", 7L);

        assertEquals(1, result.size());
        assertEquals(31L, result.getFirst().getDocumentId());
        assertEquals(7L, result.getFirst().getSchoolId());
        assertEquals("FAILED", result.getFirst().getDocumentStatus());
        assertEquals("INDEX", result.getFirst().getCurrentNode());
        assertEquals(2, result.getFirst().getRetryCount());
        assertEquals("EmbeddingUnavailable", result.getFirst().getErrorSummary());
        assertEquals(LocalDateTime.of(2026, 9, 20, 9, 30), result.getFirst().getFinishedAt());
    }
}
