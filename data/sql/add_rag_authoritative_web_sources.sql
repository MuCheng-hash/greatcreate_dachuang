-- Run once before enabling TAVILY_API_KEY. The application never applies this migration automatically.
CREATE TABLE IF NOT EXISTS rag_web_source (
    source_id BIGINT NOT NULL AUTO_INCREMENT,
    display_name VARCHAR(120) NOT NULL,
    domain VARCHAR(255) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 100,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (source_id),
    UNIQUE KEY uk_rag_web_source_domain (domain),
    KEY idx_rag_web_source_enabled_sort (enabled, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG 权威 Web 检索域名白名单';
