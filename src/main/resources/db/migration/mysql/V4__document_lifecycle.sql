CREATE TABLE document_deletion_job (
    id CHAR(36) PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    status VARCHAR(30) NOT NULL,
    current_stage VARCHAR(40) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    error_message LONGTEXT,
    requested_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_document_deletion_job_owner FOREIGN KEY (owner_id) REFERENCES app_user(id),
    UNIQUE KEY uq_document_deletion_job_active (document_id),
    KEY idx_document_deletion_job_owner (owner_id, requested_at),
    KEY idx_document_deletion_job_status (status, updated_at)
);

CREATE INDEX idx_document_status_updated ON document(status, updated_at);

UPDATE document d
SET d.status = 'OFFLINE', d.updated_at = CURRENT_TIMESTAMP(6)
WHERE d.status = 'DRAFT'
  AND d.current_version_id IS NULL
  AND EXISTS (SELECT 1 FROM document_version v WHERE v.document_id = d.id AND v.status = 'RETIRED')
  AND NOT EXISTS (SELECT 1 FROM document_version v WHERE v.document_id = d.id AND v.status = 'DRAFT');