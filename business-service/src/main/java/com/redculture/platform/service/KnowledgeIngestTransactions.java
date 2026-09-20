package com.redculture.platform.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/** 重试、删除流程与 Python 工作进程共享的 MySQL 事务和文档锁。 */
@Service
public class KnowledgeIngestTransactions {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public KnowledgeIngestTransactions(JdbcTemplate jdbc,
            @Qualifier("mysqlTransactionManager") PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
    }

    public <T> T run(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    public <T> T locked(Long documentId, Supplier<T> work) {
        return run(() -> {
            String key = "knowledge-document:" + documentId;
            Integer acquired = jdbc.queryForObject("SELECT GET_LOCK(?, 0)", Integer.class, key);
            if (!Integer.valueOf(1).equals(acquired)) {
                throw new IllegalStateException("文档入库任务正在执行，请稍后重试");
            }
            // 在事务提交或回滚后、事务绑定连接仍存在时释放锁。
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        jdbc.queryForObject("SELECT RELEASE_LOCK(?)", Integer.class, key);
                    }
                });
            return work.get();
        });
    }

    public void enqueue(Long jobId, Long documentId) {
        jdbc.update("""
            INSERT INTO knowledge_ingest_outbox(event_id, job_id, document_id, generation)
            SELECT ?, id, document_id, generation FROM knowledge_ingest_job WHERE id=? AND document_id=?
            """, UUID.randomUUID().toString(), jobId, documentId);
    }

    public void reset(Long jobId) {
        jdbc.update("""
            UPDATE knowledge_ingest_job SET generation=generation+1, execution_attempts=0,
            status='PENDING', current_node='VALIDATE', restart_from='VALIDATE', retry_count=0,
            error_summary=NULL, metadata_json=NULL, started_at=NULL, finished_at=NULL WHERE id=?
            """, jobId);
    }
}
