package com.redculture.platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import com.redculture.platform.mapper.KnowledgeDocumentMapper;
import com.redculture.platform.mapper.KnowledgeIngestJobMapper;
import com.redculture.platform.entity.KnowledgeDocument;
import com.redculture.platform.entity.KnowledgeIngestJob;
import com.redculture.platform.config.KnowledgeStorageProperties;
import com.redculture.platform.vo.AuthCurrentUserVO;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.springframework.mock.web.MockMultipartFile;

@EnabledIfEnvironmentVariable(named="INGEST_INTEGRATION", matches="true")
class KnowledgeOutboxIntegrationTest {
    JdbcTemplate jdbc;
    KnowledgeIngestTransactions transactions;
    long jobId;
    long documentId;

    @BeforeEach void setup() {
        var ds = new DriverManagerDataSource("jdbc:mysql://localhost:13316/knowledge_ingest_test?allowPublicKeyRetrieval=true&useSSL=false", "root", "integration");
        jdbc = new JdbcTemplate(ds);
        transactions = new KnowledgeIngestTransactions(jdbc, new DataSourceTransactionManager(ds));
        transactions.run(() -> {
            jdbc.update("INSERT INTO knowledge_document(title,original_filename,content_type,file_size,object_key,status,created_by) VALUES('test','test.md','text/markdown',1,'test','PENDING',1)");
            documentId=jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("INSERT INTO knowledge_ingest_job(document_id) VALUES(?)", documentId);
            jobId=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            return null;
        });
    }

    @Test void rollbackRemovesOutbox() {
        assertThrows(IllegalStateException.class, () -> transactions.run(() -> {
            transactions.enqueue(jobId,documentId); throw new IllegalStateException("rollback");
        }));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingest_outbox WHERE job_id=?",Integer.class,jobId));
    }

    @Test void uploadCommitsJobAndOutboxAndCleansFileOnRollback() throws Exception {
        var documents=mock(KnowledgeDocumentMapper.class);
        var jobs=mock(KnowledgeIngestJobMapper.class);
        var minio=mock(MinioClient.class);
        when(minio.bucketExists(any())).thenReturn(true);
        when(documents.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument doc=invocation.getArgument(0);
            jdbc.update("INSERT INTO knowledge_document(title,original_filename,content_type,file_size,object_key,created_by) VALUES(?,?,?,?,?,?)",
                doc.getTitle(),doc.getOriginalFilename(),doc.getContentType(),doc.getFileSize(),doc.getObjectKey(),doc.getCreatedBy());
            doc.setId(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)); return 1;
        });
        when(jobs.insert(any(KnowledgeIngestJob.class))).thenAnswer(invocation -> {
            KnowledgeIngestJob job=invocation.getArgument(0);
            jdbc.update("INSERT INTO knowledge_ingest_job(document_id) VALUES(?)",job.getDocumentId());
            job.setId(jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class)); return 1;
        });
        var service=new KnowledgeDocumentService(documents,jobs,mock(SchoolAccessService.class),minio,
            new KnowledgeStorageProperties(),transactions,mock(KnowledgeVectorCleanupService.class));
        var user=new AuthCurrentUserVO(); user.setAccountId(1L);
        var file=new MockMultipartFile("file","fixture.md","text/markdown","hello".getBytes());
        var uploaded=service.upload(null,"fixture",file,user);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingest_outbox WHERE document_id=?",Integer.class,uploaded.getId()));
        String rollbackTitle="rollback-"+java.util.UUID.randomUUID();
        when(jobs.insert(any(KnowledgeIngestJob.class))).thenThrow(new IllegalStateException("DB failed"));
        assertThrows(IllegalStateException.class, () -> service.upload(null,rollbackTitle,file,user));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE title=?",Integer.class,rollbackTitle));
        verify(minio).removeObject(any(RemoveObjectArgs.class));
    }

    @Test void retryAndExpiredLeasePreserveMessageIdentity() {
        transactions.run(() -> { transactions.enqueue(jobId,documentId); return null; });
        // Scope claimable fixtures to this test.
        jdbc.update("UPDATE knowledge_ingest_outbox SET status='PUBLISHED' WHERE job_id<>?",jobId);
        var attempts=new AtomicInteger();
        KnowledgeMessageSender sender=(id,body) -> {
            var payload=new ObjectMapper().readTree(body);
            assertEquals(jobId,payload.get("jobId").asLong());
            assertEquals(documentId,payload.get("documentId").asLong());
            assertEquals(id,payload.get("eventId").asText());
            if (attempts.getAndIncrement()==0) throw new IllegalStateException("MQ down");
        };
        var publisher=new KnowledgeOutboxPublisher(jdbc,transactions,sender,new ObjectMapper());
        publisher.publish();
        assertEquals("RETRY",jdbc.queryForObject("SELECT status FROM knowledge_ingest_outbox WHERE job_id=?",String.class,jobId));
        jdbc.update("UPDATE knowledge_ingest_outbox SET status='PROCESSING', lease_owner='old', lease_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE job_id=?",jobId);
        publisher.publish();
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM knowledge_ingest_outbox WHERE job_id=?",String.class,jobId));
        assertEquals(2,attempts.get());
    }

    @Test void lockExcludesConcurrentRetryAndReleasesAfterCommit() throws Exception {
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(() -> transactions.locked(documentId, () -> {
                transactions.reset(jobId); entered.countDown();
                try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return null;
            }));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try { assertThrows(IllegalStateException.class, () -> transactions.locked(documentId, () -> null)); }
            finally { release.countDown(); }
            first.get(5,TimeUnit.SECONDS);
            transactions.locked(documentId, () -> null);
        }
    }

    @Test void realRocketMqPublish() throws Exception {
        transactions.run(() -> { transactions.enqueue(jobId,documentId); return null; });
        jdbc.update("UPDATE knowledge_ingest_outbox SET status='PUBLISHED' WHERE job_id<>?",jobId);
        var sender=new RocketMqKnowledgeSender("localhost:18081","knowledge-ingest",10);
        var sends=new AtomicInteger();
        var publisher=new KnowledgeOutboxPublisher(jdbc,transactions,(id,body) -> {
            try { sender.send(id,body); } catch (Exception e) { throw new AssertionError("real MQ send failed",e); }
            if (sends.getAndIncrement()==0) throw new AssertionError("simulated process death after broker confirm");
        },new ObjectMapper());
        try {
            assertThrows(AssertionError.class,publisher::publish);
            assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM knowledge_ingest_outbox WHERE job_id=?",String.class,jobId));
            jdbc.update("UPDATE knowledge_ingest_outbox SET lease_expires_at=DATE_SUB(NOW(),INTERVAL 1 MINUTE) WHERE job_id=?",jobId);
            publisher.publish();
            assertEquals(2,sends.get());
        }
        finally { sender.close(); }
        assertEquals("PUBLISHED",jdbc.queryForObject("SELECT status FROM knowledge_ingest_outbox WHERE job_id=?",String.class,jobId));
    }

    @Test void concurrentManualRetryCreatesOneGenerationAndBlocksDeletion() throws Exception {
        jdbc.update("UPDATE knowledge_document SET status='FAILED' WHERE id=?",documentId);
        jdbc.update("UPDATE knowledge_ingest_job SET status='FAILED' WHERE id=?",jobId);
        var documents=mock(KnowledgeDocumentMapper.class);
        var jobs=mock(KnowledgeIngestJobMapper.class);
        var cleanup=mock(KnowledgeVectorCleanupService.class);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        when(documents.selectById(documentId)).thenAnswer(invocation -> {
            var doc=new KnowledgeDocument(); doc.setId(documentId);
            doc.setStatus(jdbc.queryForObject("SELECT status FROM knowledge_document WHERE id=?",String.class,documentId));
            return doc;
        });
        when(jobs.selectOne(any())).thenAnswer(invocation -> {
            var job=new KnowledgeIngestJob(); job.setId(jobId); job.setDocumentId(documentId); return job;
        });
        when(documents.updateById(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout");
            return jdbc.update("UPDATE knowledge_document SET status='PENDING' WHERE id=?",documentId);
        });
        var service=new KnowledgeDocumentService(documents,jobs,mock(SchoolAccessService.class),mock(MinioClient.class),
            new KnowledgeStorageProperties(),transactions,cleanup);
        var user=new AuthCurrentUserVO(); user.setAccountId(1L);
        try (var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(() -> service.retry(documentId,"INDEX",user));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {
                assertThrows(IllegalStateException.class,() -> service.retry(documentId,"VALIDATE",user));
                assertThrows(IllegalStateException.class,() -> service.delete(documentId,user));
                verifyNoInteractions(cleanup);
            } finally { release.countDown(); }
            first.get(5,TimeUnit.SECONDS);
        }
        assertThrows(IllegalStateException.class,() -> service.retry(documentId,"VALIDATE",user));
        assertEquals(2,jdbc.queryForObject("SELECT generation FROM knowledge_ingest_job WHERE id=?",Integer.class,jobId));
        assertEquals("VALIDATE",jdbc.queryForObject("SELECT restart_from FROM knowledge_ingest_job WHERE id=?",String.class,jobId));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_ingest_outbox WHERE job_id=?",Integer.class,jobId));
    }
}
