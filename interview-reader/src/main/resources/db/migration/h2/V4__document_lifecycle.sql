CREATE TABLE document_deletion_job (
    id CHAR(36) PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL REFERENCES app_user(id),
    status VARCHAR(30) NOT NULL,
    current_stage VARCHAR(40) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    error_code VARCHAR(100),
    error_message CLOB,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_document_deletion_job_active ON document_deletion_job(document_id);
CREATE INDEX idx_document_deletion_job_owner ON document_deletion_job(owner_id, requested_at DESC);
CREATE INDEX idx_document_deletion_job_status ON document_deletion_job(status, updated_at);
CREATE INDEX idx_document_status_updated ON document(status, updated_at);

UPDATE document d
SET status = 'OFFLINE', updated_at = CURRENT_TIMESTAMP
WHERE d.status = 'DRAFT'
  AND d.current_version_id IS NULL
  AND EXISTS (SELECT 1 FROM document_version v WHERE v.document_id = d.id AND v.status = 'RETIRED')
  AND NOT EXISTS (SELECT 1 FROM document_version v WHERE v.document_id = d.id AND v.status = 'DRAFT');