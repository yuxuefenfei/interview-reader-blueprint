CREATE TABLE app_user (
    id CHAR(36) PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE document (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    code VARCHAR(120) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description LONGTEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    current_version_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(owner_id, code),
    CONSTRAINT fk_document_owner FOREIGN KEY (owner_id) REFERENCES app_user(id)
);

CREATE TABLE document_version (
    id CHAR(36) PRIMARY KEY,
    document_id CHAR(36) NOT NULL,
    version_no INTEGER NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_file_name VARCHAR(1000),
    source_file_sha256 CHAR(64),
    converter_version VARCHAR(100),
    schema_version VARCHAR(30) NOT NULL DEFAULT '1.0',
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    language VARCHAR(20) NOT NULL DEFAULT 'zh-CN',
    metadata LONGTEXT NOT NULL,
    published_at TIMESTAMP(6) NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(document_id, version_no),
    CONSTRAINT fk_document_version_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE
);

ALTER TABLE document
    ADD CONSTRAINT fk_document_current_version
    FOREIGN KEY (current_version_id) REFERENCES document_version(id);

CREATE TABLE content_node (
    id CHAR(36) PRIMARY KEY,
    version_id CHAR(36) NOT NULL,
    parent_id CHAR(36),
    node_key VARCHAR(300) NOT NULL,
    node_type VARCHAR(30) NOT NULL,
    semantic_role VARCHAR(30),
    title VARCHAR(1000) NOT NULL,
    level SMALLINT NOT NULL,
    path VARCHAR(4000) NOT NULL,
    sort_order INTEGER NOT NULL,
    anchor VARCHAR(500) NOT NULL,
    source_page_start INTEGER,
    source_page_end INTEGER,
    source_bbox LONGTEXT,
    content_hash CHAR(64),
    search_text LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(version_id, node_key),
    UNIQUE(version_id, anchor),
    CONSTRAINT fk_content_node_version FOREIGN KEY (version_id) REFERENCES document_version(id) ON DELETE CASCADE,
    CONSTRAINT fk_content_node_parent FOREIGN KEY (parent_id) REFERENCES content_node(id) ON DELETE CASCADE
);

CREATE INDEX idx_content_node_parent ON content_node(version_id, parent_id, sort_order);
CREATE INDEX idx_content_node_path ON content_node(version_id, path(512));

CREATE TABLE content_block (
    id CHAR(36) PRIMARY KEY,
    version_id CHAR(36) NOT NULL,
    node_id CHAR(36) NOT NULL,
    block_key VARCHAR(300) NOT NULL,
    seq INTEGER NOT NULL,
    block_type VARCHAR(40) NOT NULL,
    payload LONGTEXT NOT NULL,
    plain_text LONGTEXT NOT NULL,
    language VARCHAR(50),
    source_page INTEGER,
    source_bbox LONGTEXT,
    confidence NUMERIC(5,4),
    content_hash CHAR(64),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(version_id, block_key),
    UNIQUE(node_id, seq),
    CONSTRAINT fk_content_block_version FOREIGN KEY (version_id) REFERENCES document_version(id) ON DELETE CASCADE,
    CONSTRAINT fk_content_block_node FOREIGN KEY (node_id) REFERENCES content_node(id) ON DELETE CASCADE
);

CREATE INDEX idx_content_block_node ON content_block(node_id, seq);

CREATE TABLE asset (
    id CHAR(36) PRIMARY KEY,
    version_id CHAR(36) NOT NULL,
    asset_key VARCHAR(300) NOT NULL,
    object_key VARCHAR(1500) NOT NULL,
    original_name VARCHAR(1000),
    mime_type VARCHAR(200) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width_px INTEGER,
    height_px INTEGER,
    metadata LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(version_id, asset_key),
    UNIQUE(version_id, sha256),
    CONSTRAINT fk_asset_version FOREIGN KEY (version_id) REFERENCES document_version(id) ON DELETE CASCADE
);

CREATE TABLE import_job (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_object_key VARCHAR(1500) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    converter_version VARCHAR(100) NOT NULL,
    import_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    progress SMALLINT NOT NULL DEFAULT 0,
    current_stage VARCHAR(80),
    result_version_id CHAR(36),
    error_code VARCHAR(100),
    error_message LONGTEXT,
    statistics LONGTEXT NOT NULL,
    raw_extraction_object_key VARCHAR(1500),
    raw_extraction_json LONGTEXT,
    normalized_object_key LONGTEXT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    CONSTRAINT fk_import_job_owner FOREIGN KEY (owner_id) REFERENCES app_user(id),
    CONSTRAINT fk_import_job_result_version FOREIGN KEY (result_version_id) REFERENCES document_version(id)
);

CREATE INDEX idx_import_job_owner_created ON import_job(owner_id, created_at DESC);
CREATE INDEX idx_import_job_fingerprint ON import_job(import_fingerprint);

CREATE TABLE import_issue (
    id CHAR(36) PRIMARY KEY,
    job_id CHAR(36) NOT NULL,
    severity VARCHAR(20) NOT NULL,
    issue_code VARCHAR(100) NOT NULL,
    message LONGTEXT NOT NULL,
    source_page INTEGER,
    section_key VARCHAR(300),
    block_key VARCHAR(300),
    cell_ref VARCHAR(100),
    details LONGTEXT NOT NULL,
    resolved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_import_issue_job FOREIGN KEY (job_id) REFERENCES import_job(id) ON DELETE CASCADE
);

CREATE INDEX idx_import_issue_job ON import_issue(job_id, severity, resolved);

CREATE TABLE reading_progress (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    section_id CHAR(36),
    block_id CHAR(36),
    char_offset INTEGER NOT NULL DEFAULT 0,
    block_viewport_offset INTEGER NOT NULL DEFAULT 0,
    progress_ratio NUMERIC(8,7) NOT NULL DEFAULT 0,
    client_updated_at TIMESTAMP(6) NULL,
    device_id VARCHAR(200),
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(user_id, document_id),
    CONSTRAINT fk_reading_progress_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_progress_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT fk_reading_progress_version FOREIGN KEY (version_id) REFERENCES document_version(id),
    CONSTRAINT fk_reading_progress_section FOREIGN KEY (section_id) REFERENCES content_node(id),
    CONSTRAINT fk_reading_progress_block FOREIGN KEY (block_id) REFERENCES content_block(id)
);

CREATE TABLE bookmark (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    section_id CHAR(36),
    block_id CHAR(36),
    title VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(user_id, version_id, block_id),
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT fk_bookmark_version FOREIGN KEY (version_id) REFERENCES document_version(id),
    CONSTRAINT fk_bookmark_section FOREIGN KEY (section_id) REFERENCES content_node(id),
    CONSTRAINT fk_bookmark_block FOREIGN KEY (block_id) REFERENCES content_block(id)
);

CREATE TABLE note (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    version_id CHAR(36) NOT NULL,
    section_id CHAR(36),
    block_id CHAR(36),
    selected_text LONGTEXT,
    body LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_note_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_note_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT fk_note_version FOREIGN KEY (version_id) REFERENCES document_version(id),
    CONSTRAINT fk_note_section FOREIGN KEY (section_id) REFERENCES content_node(id),
    CONSTRAINT fk_note_block FOREIGN KEY (block_id) REFERENCES content_block(id)
);

CREATE TABLE review_state (
    id CHAR(36) PRIMARY KEY,
    user_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    node_id CHAR(36) NOT NULL,
    mastery VARCHAR(20) NOT NULL,
    due_at TIMESTAMP(6) NULL,
    interval_days INTEGER,
    repetitions INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE(user_id, node_id),
    CONSTRAINT fk_review_state_user FOREIGN KEY (user_id) REFERENCES app_user(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_state_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT fk_review_state_node FOREIGN KEY (node_id) REFERENCES content_node(id) ON DELETE CASCADE
);

CREATE TABLE tag (
    id CHAR(36) PRIMARY KEY,
    owner_id CHAR(36) NOT NULL,
    name VARCHAR(200) NOT NULL,
    normalized_name VARCHAR(200) NOT NULL,
    UNIQUE(owner_id, normalized_name),
    CONSTRAINT fk_tag_owner FOREIGN KEY (owner_id) REFERENCES app_user(id) ON DELETE CASCADE
);

CREATE TABLE document_tag (
    document_id CHAR(36) NOT NULL,
    tag_id CHAR(36) NOT NULL,
    PRIMARY KEY(document_id, tag_id),
    CONSTRAINT fk_document_tag_document FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT fk_document_tag_tag FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
);

INSERT INTO app_user(id, username, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'local', 'Local User');
