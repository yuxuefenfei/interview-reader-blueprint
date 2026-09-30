ALTER TABLE document_version ADD COLUMN parent_version_no INTEGER;

UPDATE document_version
SET parent_version_no = (
    SELECT parent.version_no
    FROM document_version parent
    WHERE parent.id = document_version.parent_version_id
)
WHERE parent_version_id IS NOT NULL;

ALTER TABLE document_version DROP CONSTRAINT fk_document_version_parent;
ALTER TABLE document_version
    ADD CONSTRAINT fk_document_version_parent
    FOREIGN KEY (parent_version_id) REFERENCES document_version(id) ON DELETE SET NULL;
