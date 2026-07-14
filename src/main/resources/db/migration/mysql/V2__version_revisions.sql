ALTER TABLE document_version
    ADD COLUMN parent_version_id CHAR(36) NULL AFTER version_no,
    ADD COLUMN origin_import_job_id CHAR(36) NULL AFTER parent_version_id,
    ADD COLUMN draft_revision BIGINT NOT NULL DEFAULT 0 AFTER origin_import_job_id,
    ADD CONSTRAINT fk_document_version_parent FOREIGN KEY (parent_version_id) REFERENCES document_version(id),
    ADD CONSTRAINT fk_document_version_import_job FOREIGN KEY (origin_import_job_id) REFERENCES import_job(id);

ALTER TABLE import_job
    ADD COLUMN target_document_id CHAR(36) NULL AFTER owner_id,
    ADD CONSTRAINT fk_import_job_target_document FOREIGN KEY (target_document_id) REFERENCES document(id);

CREATE INDEX idx_document_version_state ON document_version(document_id, status, version_no);
CREATE INDEX idx_import_job_target_state ON import_job(target_document_id, status, created_at);