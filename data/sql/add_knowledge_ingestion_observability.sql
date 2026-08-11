-- Apply to databases that already ran add_knowledge_ingestion_mvp.sql.
ALTER TABLE knowledge_ingest_job
    ADD COLUMN metadata_json JSON NULL AFTER error_summary,
    ADD COLUMN restart_from VARCHAR(40) NULL AFTER metadata_json;
