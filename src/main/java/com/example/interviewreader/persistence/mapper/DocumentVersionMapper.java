package com.example.interviewreader.persistence.mapper;

import com.example.interviewreader.persistence.entity.DocumentVersionEntity;
import com.mybatisflex.core.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DocumentVersionMapper extends BaseMapper<DocumentVersionEntity> {
    @Select("SELECT COALESCE(MAX(version_no), 0) FROM document_version WHERE document_id = #{documentId}")
    int selectMaxVersionNo(@Param("documentId") String documentId);
}
