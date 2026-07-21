package com.example.interviewreader.management;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.document.DocumentMetadataPolicy;
import com.example.interviewreader.document.DocumentStatus;
import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.example.interviewreader.persistence.entity.DocumentTagEntity;
import com.example.interviewreader.persistence.entity.TagEntity;
import com.example.interviewreader.persistence.mapper.AppUserMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentTagMapper;
import com.example.interviewreader.persistence.mapper.TagMapper;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.example.interviewreader.persistence.entity.table.AppUserEntityTableDef.APP_USER_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentTagEntityTableDef.DOCUMENT_TAG_ENTITY;
import static com.example.interviewreader.persistence.entity.table.TagEntityTableDef.TAG_ENTITY;

/** 管理文档级资料；内容版本与资料修订保持相互独立。 */
@Service
@RequiredArgsConstructor
public class DocumentMetadataService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final DocumentMapper documentMapper;
    private final TagMapper tagMapper;
    private final DocumentTagMapper documentTagMapper;
    private final AppUserMapper appUserMapper;

    public DocumentMetadataDtos.DocumentMetadata get(UUID documentId) {
        return metadata(requireOwnedDocument(documentId));
    }

    @Transactional
    public DocumentMetadataDtos.DocumentMetadata update(
            UUID documentId,
            DocumentMetadataDtos.UpdateDocumentMetadataRequest request
    ) {
        var normalized = DocumentMetadataPolicy.normalize(request.title(), request.description(), request.tags());
        var current = requireOwnedDocument(documentId);
        rejectDeletionLocked(current);

        var nextRevision = request.metadataRevision() + 1;
        var update = UpdateWrapper.of(DocumentEntity.class)
                .set(DOCUMENT_ENTITY.TITLE, normalized.title())
                .set(DOCUMENT_ENTITY.DESCRIPTION, normalized.description())
                .set(DOCUMENT_ENTITY.METADATA_REVISION, nextRevision)
                .set(DOCUMENT_ENTITY.UPDATED_AT, OffsetDateTime.now());
        var updated = documentMapper.updateByQuery(
                update.toEntity(),
                false,
                QueryWrapper.create()
                        .where(DOCUMENT_ENTITY.ID.eq(documentId.toString()))
                        .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                        .and(DOCUMENT_ENTITY.METADATA_REVISION.eq(request.metadataRevision()))
                        .and(DOCUMENT_ENTITY.STATUS.notIn(DocumentStatus.DELETING, DocumentStatus.DELETE_FAILED)));
        if (updated != 1) {
            var latest = requireOwnedDocument(documentId);
            rejectDeletionLocked(latest);
            throw new ApiException(HttpStatus.CONFLICT, "METADATA_REVISION_CONFLICT", "文档资料已被其他页面修改，请刷新后重试。");
        }

        // 标签名称对单用户全局唯一；使用用户行锁串行化跨文档的新标签创建。
        var user = appUserMapper.selectOneByQuery(QueryWrapper.create()
                .select(APP_USER_ENTITY.ID)
                .from(APP_USER_ENTITY)
                .where(APP_USER_ENTITY.ID.eq(LOCAL_USER_ID))
                .forUpdate());
        if (user == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "LOCAL_USER_MISSING", "本地用户尚未初始化。");
        }
        replaceTags(documentId.toString(), normalized.tags());
        return metadata(requireOwnedDocument(documentId));
    }

    private DocumentMetadataDtos.DocumentMetadata metadata(DocumentEntity document) {
        var tags = tags(document.getId());
        var duplicateTitleCount = documentMapper.selectCountByQuery(QueryWrapper.create()
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.TITLE.eq(document.getTitle()))
                .and(DOCUMENT_ENTITY.ID.ne(document.getId())));
        return new DocumentMetadataDtos.DocumentMetadata(
                UUID.fromString(document.getId()),
                document.getCode(),
                document.getTitle(),
                document.getDescription(),
                tags,
                document.getMetadataRevision(),
                duplicateTitleCount);
    }

    private List<String> tags(String documentId) {
        var links = documentTagMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_TAG_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_TAG_ENTITY)
                .where(DOCUMENT_TAG_ENTITY.DOCUMENT_ID.eq(documentId)));
        if (links.isEmpty()) {
            return List.of();
        }
        var tagIds = links.stream().map(DocumentTagEntity::getTagId).toList();
        return tagMapper.selectListByQuery(QueryWrapper.create()
                        .select(TAG_ENTITY.ALL_COLUMNS)
                        .from(TAG_ENTITY)
                        .where(TAG_ENTITY.ID.in(tagIds)))
                .stream()
                .map(TagEntity::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private void replaceTags(String documentId, List<String> tagNames) {
        var previousLinks = documentTagMapper.selectListByQuery(QueryWrapper.create()
                .select(DOCUMENT_TAG_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_TAG_ENTITY)
                .where(DOCUMENT_TAG_ENTITY.DOCUMENT_ID.eq(documentId)));
        var previousTagIds = previousLinks.stream().map(DocumentTagEntity::getTagId).toList();
        documentTagMapper.deleteByQuery(QueryWrapper.create().where(DOCUMENT_TAG_ENTITY.DOCUMENT_ID.eq(documentId)));

        var retainedTagIds = new ArrayList<String>();
        for (var tagName : tagNames) {
            var normalizedName = tagName.toLowerCase(Locale.ROOT);
            var tag = tagMapper.selectOneByQuery(QueryWrapper.create()
                    .select(TAG_ENTITY.ALL_COLUMNS)
                    .from(TAG_ENTITY)
                    .where(TAG_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                    .and(TAG_ENTITY.NORMALIZED_NAME.eq(normalizedName)));
            if (tag == null) {
                tag = new TagEntity();
                tag.setId(UUID.randomUUID().toString());
                tag.setOwnerId(LOCAL_USER_ID);
                tag.setName(tagName);
                tag.setNormalizedName(normalizedName);
                tagMapper.insertSelective(tag);
            }
            retainedTagIds.add(tag.getId());
            var link = new DocumentTagEntity();
            link.setDocumentId(documentId);
            link.setTagId(tag.getId());
            documentTagMapper.insertSelective(link);
        }

        previousTagIds.stream()
                .filter(tagId -> !retainedTagIds.contains(tagId))
                .distinct()
                .forEach(this::deleteOrphanTag);
    }

    private void deleteOrphanTag(String tagId) {
        var references = documentTagMapper.selectCountByQuery(QueryWrapper.create()
                .from(DOCUMENT_TAG_ENTITY)
                .where(DOCUMENT_TAG_ENTITY.TAG_ID.eq(tagId)));
        if (references == 0) {
            tagMapper.deleteById(tagId);
        }
    }

    private DocumentEntity requireOwnedDocument(UUID documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.eq(documentId.toString()))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID)));
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DOCUMENT_NOT_FOUND", "文档不存在。");
        }
        return document;
    }

    private static void rejectDeletionLocked(DocumentEntity document) {
        if (DocumentStatus.isDeletionLocked(document.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_DELETION_LOCKED", "永久删除流程已锁定当前文档。");
        }
    }
}
