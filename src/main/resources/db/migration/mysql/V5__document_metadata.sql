ALTER TABLE document
    ADD COLUMN metadata_revision BIGINT NOT NULL DEFAULT 0 AFTER description;
