CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(200),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE document (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    code VARCHAR(120) NOT NULL,
    title VARCHAR(500) NOT NULL,
    description CLOB,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    current_version_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(owner_id, code)
);

CREATE TABLE document_version (
    id UUID PRIMARY KEY,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    source_type VARCHAR(30) NOT NULL,
    source_file_name VARCHAR(1000),
    source_file_sha256 CHAR(64),
    converter_version VARCHAR(100),
    schema_version VARCHAR(30) NOT NULL DEFAULT '1.0',
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    language VARCHAR(20) NOT NULL DEFAULT 'zh-CN',
    metadata CLOB NOT NULL DEFAULT '{}',
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(document_id, version_no)
);

ALTER TABLE document
    ADD CONSTRAINT fk_document_current_version
    FOREIGN KEY (current_version_id) REFERENCES document_version(id);

CREATE TABLE content_node (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    parent_id UUID REFERENCES content_node(id) ON DELETE CASCADE,
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
    source_bbox CLOB,
    content_hash CHAR(64),
    search_text CLOB NOT NULL DEFAULT '',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(version_id, node_key),
    UNIQUE(version_id, anchor)
);

CREATE INDEX idx_content_node_parent ON content_node(version_id, parent_id, sort_order);
CREATE INDEX idx_content_node_path ON content_node(version_id, path);

CREATE TABLE content_block (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    node_id UUID NOT NULL REFERENCES content_node(id) ON DELETE CASCADE,
    block_key VARCHAR(300) NOT NULL,
    seq INTEGER NOT NULL,
    block_type VARCHAR(40) NOT NULL,
    payload CLOB NOT NULL,
    plain_text CLOB NOT NULL DEFAULT '',
    language VARCHAR(50),
    source_page INTEGER,
    source_bbox CLOB,
    confidence NUMERIC(5,4),
    content_hash CHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(version_id, block_key),
    UNIQUE(node_id, seq)
);

CREATE INDEX idx_content_block_node ON content_block(node_id, seq);

CREATE TABLE asset (
    id UUID PRIMARY KEY,
    version_id UUID NOT NULL REFERENCES document_version(id) ON DELETE CASCADE,
    asset_key VARCHAR(300) NOT NULL,
    object_key VARCHAR(1500) NOT NULL,
    original_name VARCHAR(1000),
    mime_type VARCHAR(200) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width_px INTEGER,
    height_px INTEGER,
    metadata CLOB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(version_id, asset_key),
    UNIQUE(version_id, sha256)
);

CREATE TABLE import_job (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id),
    source_type VARCHAR(30) NOT NULL,
    source_object_key VARCHAR(1500) NOT NULL,
    source_sha256 CHAR(64) NOT NULL,
    converter_version VARCHAR(100) NOT NULL,
    import_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(40) NOT NULL,
    progress SMALLINT NOT NULL DEFAULT 0,
    current_stage VARCHAR(80),
    result_version_id UUID REFERENCES document_version(id),
    error_code VARCHAR(100),
    error_message CLOB,
    statistics CLOB NOT NULL DEFAULT '{}',
    raw_extraction_object_key VARCHAR(1500),
    raw_extraction_json CLOB,
    normalized_object_key CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_import_job_owner_created ON import_job(owner_id, created_at DESC);
CREATE INDEX idx_import_job_fingerprint ON import_job(import_fingerprint);

CREATE TABLE import_issue (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES import_job(id) ON DELETE CASCADE,
    severity VARCHAR(20) NOT NULL,
    issue_code VARCHAR(100) NOT NULL,
    message CLOB NOT NULL,
    source_page INTEGER,
    section_key VARCHAR(300),
    block_key VARCHAR(300),
    cell_ref VARCHAR(100),
    details CLOB NOT NULL DEFAULT '{}',
    resolved BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_import_issue_job ON import_issue(job_id, severity, resolved);

CREATE TABLE reading_progress (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES document_version(id),
    section_id UUID REFERENCES content_node(id),
    block_id UUID REFERENCES content_block(id),
    char_offset INTEGER NOT NULL DEFAULT 0,
    block_viewport_offset INTEGER NOT NULL DEFAULT 0,
    progress_ratio NUMERIC(8,7) NOT NULL DEFAULT 0,
    client_updated_at TIMESTAMP WITH TIME ZONE,
    device_id VARCHAR(200),
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, document_id)
);

CREATE TABLE bookmark (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES document_version(id),
    section_id UUID REFERENCES content_node(id),
    block_id UUID REFERENCES content_block(id),
    title VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, version_id, block_id)
);

CREATE TABLE note (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    version_id UUID NOT NULL REFERENCES document_version(id),
    section_id UUID REFERENCES content_node(id),
    block_id UUID REFERENCES content_block(id),
    selected_text CLOB,
    body CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE review_state (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    node_id UUID NOT NULL REFERENCES content_node(id) ON DELETE CASCADE,
    mastery VARCHAR(20) NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE,
    interval_days INTEGER,
    repetitions INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, node_id)
);

CREATE TABLE tag (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    normalized_name VARCHAR(200) NOT NULL,
    UNIQUE(owner_id, normalized_name)
);

CREATE TABLE document_tag (
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    tag_id UUID NOT NULL REFERENCES tag(id) ON DELETE CASCADE,
    PRIMARY KEY(document_id, tag_id)
);

INSERT INTO app_user(id, username, display_name)
VALUES ('00000000-0000-0000-0000-000000000001', 'local', 'Local User');
