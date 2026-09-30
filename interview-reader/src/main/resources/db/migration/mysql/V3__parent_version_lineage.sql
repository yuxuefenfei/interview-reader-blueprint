ALTER TABLE document_version
    ADD COLUMN parent_version_no INTEGER NULL AFTER parent_version_id;

UPDATE document_version child
JOIN document_version parent ON parent.id = child.parent_version_id
SET child.parent_version_no = parent.version_no;

ALTER TABLE document_version DROP FOREIGN KEY fk_document_version_parent;
ALTER TABLE document_version
    ADD CONSTRAINT fk_document_version_parent
    FOREIGN KEY (parent_version_id) REFERENCES document_version(id) ON DELETE SET NULL;
