package com.example.interviewreader.persistence.mapper;

import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DocumentMapper extends BaseMapper<DocumentEntity> {
    @Select("SELECT id, owner_id AS ownerId, code, title, description, status, " +
            "metadata_revision AS metadataRevision, current_version_id AS currentVersionId, " +
            "created_at AS createdAt, updated_at AS updatedAt " +
            "FROM document WHERE id = #{documentId} AND owner_id = #{ownerId} FOR UPDATE")
    DocumentEntity selectOwnedForUpdate(@Param("documentId") String documentId, @Param("ownerId") String ownerId);
}