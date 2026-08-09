-- Knowledge document ingestion MVP. Run after the existing platform schema.
CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    school_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 CHAR(64) NULL,
    object_key VARCHAR(512) NOT NULL,
    markdown_object_key VARCHAR(512) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    published_at DATETIME NULL,
    indexed_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_knowledge_document_scope_hash (school_id, sha256),
    INDEX idx_knowledge_document_status (status)
);

CREATE TABLE IF NOT EXISTS knowledge_ingest_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    current_node VARCHAR(40) NOT NULL DEFAULT 'VALIDATE',
    retry_count INT NOT NULL DEFAULT 0,
    error_summary VARCHAR(1000) NULL,
    started_at DATETIME NULL,
    finished_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_ingest_job_document (document_id),
    CONSTRAINT fk_knowledge_ingest_job_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    chunk_order INT NOT NULL,
    title_path VARCHAR(1000) NULL,
    content MEDIUMTEXT NOT NULL,
    token_count INT NOT NULL,
    subject VARCHAR(255) NULL,
    subject_type VARCHAR(100) NULL,
    tags JSON NULL,
    qdrant_point_id VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_chunk_document_order (document_id, chunk_order),
    INDEX idx_knowledge_chunk_document (document_id),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_document_image (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    document_id BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    alt_text VARCHAR(1000) NULL,
    description MEDIUMTEXT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    model VARCHAR(255) NULL,
    error_summary VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_knowledge_image_document_hash (document_id, sha256),
    INDEX idx_knowledge_image_hash (sha256),
    CONSTRAINT fk_knowledge_image_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id) ON DELETE CASCADE
);
