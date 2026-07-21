package com.example.interviewreader.importpkg;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.common.Hashes;
import com.example.interviewreader.document.DocumentMetadataPolicy;
import com.example.interviewreader.document.DocumentStatus;
import com.example.interviewreader.document.DocumentVersionStatus;
import com.example.interviewreader.document.SourceType;
import com.example.interviewreader.excelpkg.ExcelPackageService;
import com.example.interviewreader.markdownpkg.MarkdownPackageService;
import com.example.interviewreader.pdfpkg.PdfPackageService;
import com.example.interviewreader.persistence.entity.AssetEntity;
import com.example.interviewreader.persistence.entity.ContentBlockEntity;
import com.example.interviewreader.persistence.entity.ContentNodeEntity;
import com.example.interviewreader.persistence.entity.DocumentEntity;
import com.example.interviewreader.persistence.entity.DocumentTagEntity;
import com.example.interviewreader.persistence.entity.DocumentVersionEntity;
import com.example.interviewreader.persistence.entity.ImportIssueEntity;
import com.example.interviewreader.persistence.entity.ImportJobEntity;
import com.example.interviewreader.persistence.entity.TagEntity;
import com.example.interviewreader.persistence.mapper.AppUserMapper;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.example.interviewreader.persistence.mapper.ContentBlockMapper;
import com.example.interviewreader.persistence.mapper.ContentNodeMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentTagMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.example.interviewreader.persistence.mapper.ImportIssueMapper;
import com.example.interviewreader.persistence.mapper.ImportJobMapper;
import com.example.interviewreader.persistence.mapper.TagMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mybatisflex.core.query.QueryWrapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import static com.example.interviewreader.persistence.entity.table.AppUserEntityTableDef.APP_USER_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ContentNodeEntityTableDef.CONTENT_NODE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentTagEntityTableDef.DOCUMENT_TAG_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ImportIssueEntityTableDef.IMPORT_ISSUE_ENTITY;
import static com.example.interviewreader.persistence.entity.table.ImportJobEntityTableDef.IMPORT_JOB_ENTITY;
import static com.example.interviewreader.persistence.entity.table.TagEntityTableDef.TAG_ENTITY;

@Service
public class ImportPackageService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final ObjectMapper objectMapper;
    private final DocumentPackageValidator validator;
    private final DocumentPackageNormalizer normalizer;
    private final ExcelPackageService excelPackageService;
    private final MarkdownPackageService markdownPackageService;
    private final PdfPackageService pdfPackageService;
    private final SourceFileStorage sourceFileStorage;
    private final ImportJobWorker importJobWorker;
    private final ImportJobMapper importJobMapper;
    private final AppUserMapper appUserMapper;
    private final ImportIssueMapper importIssueMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final ContentNodeMapper contentNodeMapper;
    private final ContentBlockMapper contentBlockMapper;
    private final AssetMapper assetMapper;
    private final TagMapper tagMapper;
    private final DocumentTagMapper documentTagMapper;
    private final String converterVersion;

    public ImportPackageService(
            ObjectMapper objectMapper,
            DocumentPackageValidator validator,
            DocumentPackageNormalizer normalizer,
            ExcelPackageService excelPackageService,
            MarkdownPackageService markdownPackageService,
            PdfPackageService pdfPackageService,
            SourceFileStorage sourceFileStorage,
            ImportJobWorker importJobWorker,
            ImportJobMapper importJobMapper,
            AppUserMapper appUserMapper,
            ImportIssueMapper importIssueMapper,
            DocumentMapper documentMapper,
            DocumentVersionMapper documentVersionMapper,
            ContentNodeMapper contentNodeMapper,
            ContentBlockMapper contentBlockMapper,
            AssetMapper assetMapper,
            TagMapper tagMapper,
            DocumentTagMapper documentTagMapper,
            ImportProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.normalizer = normalizer;
        this.excelPackageService = excelPackageService;
        this.markdownPackageService = markdownPackageService;
        this.pdfPackageService = pdfPackageService;
        this.sourceFileStorage = sourceFileStorage;
        this.importJobWorker = importJobWorker;
        this.importJobMapper = importJobMapper;
        this.appUserMapper = appUserMapper;
        this.importIssueMapper = importIssueMapper;
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.contentNodeMapper = contentNodeMapper;
        this.contentBlockMapper = contentBlockMapper;
        this.assetMapper = assetMapper;
        this.tagMapper = tagMapper;
        this.documentTagMapper = documentTagMapper;
        this.converterVersion = properties.converterVersion();
    }

    @Transactional
    public ImportJobDto createImportJob(MultipartFile file, UUID targetDocumentId) {
        var fileBytes = readBytes(file);
        var normalizedSourceType = detectSourceType(file.getOriginalFilename(), fileBytes);
        var sourceSha256 = Hashes.sha256(fileBytes);
        var targetId = targetDocumentId == null ? null : id(targetDocumentId);
        if (targetId != null) {
            var target = documentMapper.selectOneByQuery(QueryWrapper.create()
                    .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                    .from(DOCUMENT_ENTITY)
                    .where(DOCUMENT_ENTITY.ID.eq(targetId))
                    .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID)));
            if (target == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Target document not found");
            }
            rejectDeletionLocked(target);
        }
        var importFingerprint = Hashes.sha256(sourceSha256 + ":" + normalizedSourceType.getCode() + ":" + converterVersion + ":" + Objects.toString(targetId, "NEW"));
        // 单用户导入以用户行作为互斥键，保证“检查可复用任务 + 新建任务”在并发上传时原子化。
        lockLocalUser();

        var existingJob = findReusableImportJobByFingerprint(importFingerprint);
        if (existingJob != null) {
            return existingJob;
        }

        var jobId = UUID.randomUUID();
        var sourceFileName = normalizedFileName(file.getOriginalFilename(), defaultFileName(normalizedSourceType));
        var sourceObjectKey = sourceFileStorage.save(fileBytes, sourceSha256, sourceFileName);
        var statistics = new LinkedHashMap<String, Object>();
        statistics.put("bytes", fileBytes.length);
        statistics.put("sourceFileName", sourceFileName);
        statistics.put("workerMode", importJobWorker.isEnabled() ? "ASYNC" : "INLINE");

        var job = new ImportJobEntity();
        job.setId(jobId.toString());
        job.setOwnerId(LOCAL_USER_ID);
        job.setTargetDocumentId(targetId);
        job.setSourceType(normalizedSourceType);
        job.setSourceObjectKey(sourceObjectKey);
        job.setSourceSha256(sourceSha256);
        job.setConverterVersion(converterVersion);
        job.setImportFingerprint(importFingerprint);
        job.setStatus(ImportJobStatus.UPLOADED);
        job.setProgress(5);
        job.setCurrentStage(ImportStage.UPLOADED);
        job.setStatistics(toJson(statistics));
        job.setStartedAt(OffsetDateTime.now());
        importJobMapper.insertSelective(job);

        scheduleImportProcessing(jobId, normalizedSourceType, fileBytes, sourceFileName, sourceSha256, statistics);
        return getImportJob(jobId);
    }

    public void processImportJob(
            UUID jobId,
            SourceType sourceType,
            byte[] fileBytes,
            String sourceFileName,
            String sourceSha256,
            Map<String, Object> baseStatistics
    ) {
        try {
            updateImportStage(jobId, ImportStage.PREFLIGHT, 15, baseStatistics);
            updateImportStage(jobId, ImportStage.EXTRACTING, 35, baseStatistics);
            var parsed = parseSource(sourceType, fileBytes, sourceFileName, sourceSha256);
            updateImportStage(jobId, ImportStage.NORMALIZING, 55, baseStatistics);
            var normalization = normalizer.normalize(parsed.documentPackage());
            var documentPackage = normalization.documentPackage();
            var issues = new ArrayList<>(parsed.issues());
            issues.addAll(normalization.issues());
            if (documentPackage != null) {
                issues.addAll(validator.validate(documentPackage));
            }
            updateImportStage(jobId, ImportStage.VALIDATING, 75, baseStatistics);
            ensureNotCanceled(jobId);
            var statistics = new LinkedHashMap<>(baseStatistics);
            statistics.put("removedEmptyBlockCount", normalization.issues().size());
            statistics.put("issueCount", issues.size());
            statistics.put("blockingIssueCount", issues.stream().filter(issue -> issue.severity() == ImportIssueSeverity.BLOCKING).count());
            if (documentPackage != null) {
                statistics.put("sectionCount", documentPackage.sections().size());
                statistics.put("blockCount", documentPackage.blocks().size());
            }

            for (var issue : issues) {
                ensureNotCanceled(jobId);
                insertIssue(jobId, issue);
            }
            ensureNotCanceled(jobId);
            var job = requireJob(jobId);
            var status = issues.stream().anyMatch(issue -> issue.severity() == ImportIssueSeverity.BLOCKING)
                    ? ImportJobStatus.REVIEW_REQUIRED : ImportJobStatus.READY;
            job.setStatus(status);
            job.setProgress(100);
            job.setCurrentStage(ImportStage.resultStage(status));
            job.setStatistics(toJson(statistics));
            job.setRawExtractionJson(toJsonOrNull(parsed.rawExtraction()));
            job.setNormalizedObjectKey(documentPackage == null ? "{}" : toJson(documentPackage));
            job.setFinishedAt(OffsetDateTime.now());
            importJobMapper.update(job);
        } catch (ImportCanceledException ignored) {
        } catch (RuntimeException exception) {
            var job = job(jobId);
            if (job != null && job.getStatus() != ImportJobStatus.CANCELED) {
                job.setStatus(ImportJobStatus.FAILED);
                job.setProgress(100);
                job.setCurrentStage(ImportStage.FAILED);
                job.setErrorCode("IMPORT_FAILED");
                job.setErrorMessage(exception.getMessage());
                job.setFinishedAt(OffsetDateTime.now());
                importJobMapper.update(job);
            }
        }
    }

    @Transactional
    public ImportJobDto cancel(UUID jobId) {
        var dto = getImportJob(jobId);
        if (!ImportJobStatus.isCancelable(dto.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active import jobs can be canceled");
        }
        importJobWorker.cancel(jobId);
        var job = requireJob(jobId);
        if (ImportJobStatus.isCancelable(job.getStatus())) {
            job.setStatus(ImportJobStatus.CANCELED);
            job.setProgress(100);
            job.setCurrentStage(ImportStage.CANCELED);
            job.setErrorCode(null);
            job.setErrorMessage(null);
            job.setFinishedAt(OffsetDateTime.now());
            importJobMapper.update(job);
        }
        var canceled = getImportJob(jobId);
        if (canceled.status() != ImportJobStatus.CANCELED) {
            throw new ApiException(HttpStatus.CONFLICT, "Only active import jobs can be canceled");
        }
        return canceled;
    }

    public ImportJobDto getImportJob(UUID jobId) {
        var job = job(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import job not found");
        }
        return mapJob(job);
    }

    public List<ImportIssueDto> listIssues(UUID jobId) {
        return importIssueMapper.selectListByQuery(QueryWrapper.create()
                        .select(IMPORT_ISSUE_ENTITY.ALL_COLUMNS)
                        .from(IMPORT_ISSUE_ENTITY)
                        .where(IMPORT_ISSUE_ENTITY.JOB_ID.eq(id(jobId)))
                        .orderBy(IMPORT_ISSUE_ENTITY.CREATED_AT.asc(), IMPORT_ISSUE_ENTITY.ISSUE_CODE.asc()))
                .stream()
                .map(this::mapIssue)
                .toList();
    }

    public JsonNode rawExtraction(UUID jobId) {
        var job = requireJob(jobId);
        return snapshot(job.getRawExtractionJson(), "raw extraction");
    }

    public JsonNode normalizedPackage(UUID jobId) {
        var job = requireJob(jobId);
        return snapshot(job.getNormalizedObjectKey(), "normalized package");
    }

    public SourceFileStorage.SourceFile sourceFile(UUID jobId) {
        return sourceFileStorage.load(requireJob(jobId).getSourceObjectKey());
    }

    public ImportDocumentDtos.ImportDocumentPreview documentMetadata(UUID jobId) {
        var job = requireJob(jobId);
        var documentPackage = readPackage(job.getNormalizedObjectKey());
        var documentInfo = documentPackage.document();
        var matchingDocument = job.getTargetDocumentId() == null
                ? findDocumentByCode(documentInfo.documentKey())
                : null;
        return new ImportDocumentDtos.ImportDocumentPreview(
                documentInfo.documentKey(),
                documentInfo.title(),
                documentInfo.description(),
                documentInfo.tags() == null ? List.of() : documentInfo.tags(),
                job.getTargetDocumentId() == null,
                matchingDocument == null ? null : new ImportDocumentDtos.ExistingDocumentMatch(
                        UUID.fromString(matchingDocument.getId()),
                        matchingDocument.getCode(),
                        matchingDocument.getTitle(),
                        matchingDocument.getStatus()),
                job.getTargetDocumentId() == null ? nextAvailableDocumentKey(documentInfo.documentKey()) : null,
                job.getTargetDocumentId() == null ? duplicateTitleCount(documentInfo.title()) : 0);
    }

    @Transactional
    public ImportDocumentDtos.ImportDocumentPreview reviseDocumentMetadata(
            UUID jobId,
            ImportDocumentDtos.UpdateImportDocumentMetadataRequest request
    ) {
        var job = loadJobForRevision(jobId);
        if (job.getTargetDocumentId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_TARGET_METADATA_READ_ONLY", "导入已有文档时不能修改文档级资料。");
        }
        var normalized = DocumentMetadataPolicy.normalize(request.title(), request.description(), request.tags());
        var root = readTree(job.getNormalizedObjectKey());
        if (!(root.path("document") instanceof ObjectNode documentNode)) {
            throw new ApiException(HttpStatus.CONFLICT, "STAGED_DOCUMENT_MISSING", "导入快照缺少文档资料。");
        }
        documentNode.put("title", normalized.title());
        if (normalized.description() == null) {
            documentNode.putNull("description");
        } else {
            documentNode.put("description", normalized.description());
        }
        documentNode.set("tags", objectMapper.valueToTree(normalized.tags()));
        job.setNormalizedObjectKey(toJson(root));
        job.setCurrentStage(ImportStage.REVIEWING);
        importJobMapper.update(job);
        return documentMetadata(jobId);
    }
    @Transactional
    public JsonNode reviseSection(UUID jobId, String sectionKey, JsonNode patch) {
        return reviseNormalizedItem(jobId, "sections", "sectionKey", sectionKey, patch);
    }

    @Transactional
    public JsonNode reviseBlock(UUID jobId, String blockKey, JsonNode patch) {
        return reviseNormalizedItem(jobId, "blocks", "blockKey", blockKey, patch);
    }

    @Transactional
    public DocumentVersionDto commit(UUID jobId) {
        return commit(jobId, null);
    }

    @Transactional
    public DocumentVersionDto commit(UUID jobId, ImportDocumentDtos.CommitImportRequest request) {
        var job = loadJobForCommit(jobId);
        if (job.getStatus() == ImportJobStatus.IMPORTED && job.getResultVersionId() != null) {
            return getVersion(UUID.fromString(job.getResultVersionId()));
        }
        if (job.getStatus() != ImportJobStatus.READY) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_NOT_READY", "只有待提交的导入任务才能生成草稿。");
        }

        var documentPackage = readPackage(job.getNormalizedObjectKey());
        var issues = validator.validate(documentPackage);
        if (!issues.isEmpty()) {
            issues.forEach(issue -> insertIssue(jobId, issue));
            job.setStatus(ImportJobStatus.REVIEW_REQUIRED);
            job.setCurrentStage(ImportStage.VALIDATING);
            importJobMapper.update(job);
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_REVIEW_REQUIRED", "导入包仍有阻断性校验问题。");
        }

        var resolution = request == null ? null : request.resolution();
        var now = OffsetDateTime.now();
        String documentId;
        if (job.getTargetDocumentId() != null) {
            if (resolution != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "IMPORT_RESOLUTION_NOT_ALLOWED", "已指定目标文档的任务不接受冲突决议。");
            }
            documentId = requireImportTargetForUpdate(job.getTargetDocumentId()).getId();
        } else {
            var documentKey = documentPackage.document().documentKey().strip();
            var matchingDocument = findDocumentByCode(documentKey);
            if (matchingDocument != null && resolution == null) {
                throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_CODE_CONFLICT", "文档标识已存在，请明确选择创建新文档或导入为已有文档的新版本。");
            }
            if (resolution == ImportResolution.IMPORT_AS_NEW_VERSION) {
                if (matchingDocument == null) {
                    throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_CODE_MATCH_MISSING", "匹配的目标文档已不存在，请刷新导入预览。");
                }
                documentId = requireImportTargetForUpdate(matchingDocument.getId()).getId();
            } else {
                lockLocalUser();
                var normalizedMetadata = DocumentMetadataPolicy.normalize(
                        documentPackage.document().title(),
                        documentPackage.document().description(),
                        documentPackage.document().tags());
                var document = new DocumentEntity();
                documentId = UUID.randomUUID().toString();
                document.setId(documentId);
                document.setOwnerId(LOCAL_USER_ID);
                document.setCode(nextAvailableDocumentKey(documentKey));
                document.setTitle(normalizedMetadata.title());
                document.setDescription(normalizedMetadata.description());
                document.setMetadataRevision(0);
                document.setStatus(DocumentStatus.DRAFT);
                documentMapper.insertSelective(document);
                upsertTags(documentId, normalizedMetadata.tags());
            }
        }

        var versionId = UUID.randomUUID().toString();
        var version = new DocumentVersionEntity();
        version.setId(versionId);
        version.setDocumentId(documentId);
        version.setVersionNo(nextVersionNo(documentId));
        version.setParentVersionId(currentVersionId(documentId));
        version.setOriginImportJobId(job.getId());
        version.setDraftRevision(0);
        version.setSourceType(documentPackage.version().sourceType());
        version.setSourceFileName(documentPackage.version().sourceFileName());
        version.setSourceFileSha256(documentPackage.version().sourceSha256());
        version.setConverterVersion(Objects.requireNonNullElse(documentPackage.version().converterVersion(), converterVersion));
        version.setSchemaVersion(documentPackage.schemaVersion());
        version.setStatus(DocumentVersionStatus.DRAFT);
        version.setLanguage(Objects.requireNonNullElse(documentPackage.document().language(), "zh-CN"));
        version.setMetadata(toJson(Objects.requireNonNullElse(documentPackage.version().metadata(), Map.of())));
        documentVersionMapper.insertSelective(version);

        insertSections(versionId, documentPackage);
        insertBlocks(versionId, documentPackage);
        insertAssets(versionId, documentPackage);

        var document = documentMapper.selectOneById(documentId);
        document.setUpdatedAt(now);
        documentMapper.update(document);

        job.setTargetDocumentId(documentId);
        job.setStatus(ImportJobStatus.IMPORTED);
        job.setResultVersionId(versionId);
        job.setCurrentStage(ImportStage.COMMITTED);
        importJobMapper.update(job);
        return new DocumentVersionDto(UUID.fromString(versionId), UUID.fromString(documentId), version.getVersionNo(), DocumentVersionStatus.DRAFT, documentPackage.schemaVersion());
    }

    private void insertSections(String versionId, DocumentPackage documentPackage) {
        var sections = new ArrayList<>(documentPackage.sections());
        sections.sort(Comparator
                .comparing(DocumentPackage.SectionInfo::level)
                .thenComparing(section -> Objects.requireNonNullElse(section.sortOrder(), 0))
                .thenComparing(DocumentPackage.SectionInfo::sectionKey));
        var nodeIds = new HashMap<String, String>();
        var paths = new HashMap<String, String>();
        var blockTextBySection = blockTextBySection(documentPackage.blocks());

        for (var section : sections) {
            var id = UUID.randomUUID().toString();
            var parentId = isBlank(section.parentSectionKey()) ? null : nodeIds.get(section.parentSectionKey());
            if (!isBlank(section.parentSectionKey()) && parentId == null) {
                throw new ApiException(HttpStatus.CONFLICT, "Parent section was not inserted: " + section.parentSectionKey());
            }
            var parentPath = isBlank(section.parentSectionKey()) ? null : paths.get(section.parentSectionKey());
            var pathPart = String.format("%06d", section.sortOrder());
            var path = parentPath == null ? pathPart : parentPath + "." + pathPart;
            var anchor = isBlank(section.anchor()) ? slug(section.sectionKey()) : section.anchor();
            var searchText = section.title() + "\n" + String.join("\n", blockTextBySection.getOrDefault(section.sectionKey(), List.of()));

            var node = new ContentNodeEntity();
            node.setId(id);
            node.setVersionId(versionId);
            node.setParentId(parentId);
            node.setNodeKey(section.sectionKey());
            node.setNodeType(section.nodeType());
            node.setSemanticRole(section.semanticRole());
            node.setTitle(section.title());
            node.setLevel(section.level());
            node.setPath(path);
            node.setSortOrder(section.sortOrder());
            node.setAnchor(anchor);
            node.setSourcePageStart(section.sourcePageStart());
            node.setSourcePageEnd(section.sourcePageEnd());
            node.setSourceBbox(toJsonOrNull(section.sourceBbox()));
            node.setContentHash(emptyToNull(section.contentHash()));
            node.setSearchText(searchText);
            contentNodeMapper.insertSelective(node);
            nodeIds.put(section.sectionKey(), id);
            paths.put(section.sectionKey(), path);
        }
    }

    private void insertBlocks(String versionId, DocumentPackage documentPackage) {
        var nodeIds = contentNodeMapper.selectListByQuery(QueryWrapper.create()
                        .select(CONTENT_NODE_ENTITY.ALL_COLUMNS)
                        .from(CONTENT_NODE_ENTITY)
                        .where(CONTENT_NODE_ENTITY.VERSION_ID.eq(versionId)))
                .stream()
                .collect(HashMap<String, String>::new, (map, node) -> map.put(node.getNodeKey(), node.getId()), HashMap::putAll);

        for (var block : documentPackage.blocks()) {
            var entity = new ContentBlockEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setVersionId(versionId);
            entity.setNodeId(nodeIds.get(block.sectionKey()));
            entity.setBlockKey(block.blockKey());
            entity.setSeq(block.seq());
            entity.setBlockType(block.blockType());
            entity.setPayload(toJson(block.payload()));
            entity.setPlainText(Objects.requireNonNullElse(block.plainText(), ""));
            entity.setLanguage(emptyToNull(block.language()));
            entity.setSourcePage(block.sourcePage());
            entity.setSourceBbox(toJsonOrNull(block.sourceBbox()));
            entity.setConfidence(block.confidence());
            entity.setContentHash(emptyToNull(block.contentHash()));
            contentBlockMapper.insertSelective(entity);
        }
    }

    private void insertAssets(String versionId, DocumentPackage documentPackage) {
        for (var asset : documentPackage.assets()) {
            var entity = new AssetEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setVersionId(versionId);
            entity.setAssetKey(asset.assetKey());
            entity.setObjectKey(asset.path());
            entity.setOriginalName(asset.path());
            entity.setMimeType(asset.mimeType());
            entity.setSha256(asset.sha256().toLowerCase(Locale.ROOT));
            entity.setSizeBytes(0);
            entity.setMetadata(toJson(Map.of("alt", Objects.requireNonNullElse(asset.alt(), ""))));
            assetMapper.insertSelective(entity);
        }
    }

    private void upsertTags(String documentId, List<String> tags) {
        if (tags == null) {
            return;
        }
        for (var tag : tags.stream().filter(value -> !isBlank(value)).distinct().toList()) {
            var normalized = tag.trim().toLowerCase(Locale.ROOT);
            var tagId = findTagId(normalized);
            if (tagId == null) {
                tagId = UUID.randomUUID().toString();
                var entity = new TagEntity();
                entity.setId(tagId);
                entity.setOwnerId(LOCAL_USER_ID);
                entity.setName(tag.trim());
                entity.setNormalizedName(normalized);
                tagMapper.insertSelective(entity);
            }
            var link = documentTagMapper.selectOneByQuery(QueryWrapper.create()
                    .select(DOCUMENT_TAG_ENTITY.ALL_COLUMNS)
                    .from(DOCUMENT_TAG_ENTITY)
                    .where(DOCUMENT_TAG_ENTITY.DOCUMENT_ID.eq(documentId))
                    .and(DOCUMENT_TAG_ENTITY.TAG_ID.eq(tagId)));
            if (link == null) {
                var entity = new DocumentTagEntity();
                entity.setDocumentId(documentId);
                entity.setTagId(tagId);
                documentTagMapper.insertSelective(entity);
            }
        }
    }

    private Map<String, List<String>> blockTextBySection(List<DocumentPackage.BlockInfo> blocks) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var block : blocks) {
            result.computeIfAbsent(block.sectionKey(), ignored -> new ArrayList<>())
                    .add(Objects.requireNonNullElse(block.plainText(), ""));
        }
        return result;
    }

    private static void rejectDeletionLocked(DocumentEntity document) {
        if (document != null && DocumentStatus.isDeletionLocked(document.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_DELETION_LOCKED", "Document is locked by permanent deletion");
        }
    }
    private long duplicateTitleCount(String title) {
        return documentMapper.selectCountByQuery(QueryWrapper.create()
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.TITLE.eq(title)));
    }
    private DocumentEntity findDocumentByCode(String documentKey) {
        return documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.CODE.eq(documentKey)));
    }

    private DocumentEntity requireImportTargetForUpdate(String documentId) {
        var document = documentMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_ENTITY.ALL_COLUMNS)
                .from(DOCUMENT_ENTITY)
                .where(DOCUMENT_ENTITY.ID.eq(documentId))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .forUpdate());
        if (document == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TARGET_DOCUMENT_NOT_FOUND", "目标文档不存在。");
        }
        rejectDeletionLocked(document);
        return document;
    }

    private String nextAvailableDocumentKey(String requestedKey) {
        var base = requestedKey == null ? "" : requestedKey.strip();
        if (base.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DOCUMENT_KEY_REQUIRED", "文档标识不能为空。");
        }
        base = truncateDocumentKey(base, "");
        if (findDocumentByCode(base) == null) {
            return base;
        }
        for (var sequence = 2; sequence < Integer.MAX_VALUE; sequence++) {
            var suffix = "-" + sequence;
            var candidate = truncateDocumentKey(base, suffix) + suffix;
            if (findDocumentByCode(candidate) == null) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_KEY_EXHAUSTED", "无法生成可用的文档标识。");
    }

    private static String truncateDocumentKey(String base, String suffix) {
        var maxBaseLength = 120 - suffix.length();
        return base.length() <= maxBaseLength ? base : base.substring(0, maxBaseLength);
    }

    private void lockLocalUser() {
        var user = appUserMapper.selectOneByQuery(QueryWrapper.create()
                .select(APP_USER_ENTITY.ID)
                .from(APP_USER_ENTITY)
                .where(APP_USER_ENTITY.ID.eq(LOCAL_USER_ID))
                .forUpdate());
        if (user == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "LOCAL_USER_MISSING", "本地用户尚未初始化。");
        }
    }

    private String findTagId(String normalized) {
        var tag = tagMapper.selectOneByQuery(QueryWrapper.create()
                .select(TAG_ENTITY.ALL_COLUMNS)
                .from(TAG_ENTITY)
                .where(TAG_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(TAG_ENTITY.NORMALIZED_NAME.eq(normalized)));
        return tag == null ? null : tag.getId();
    }

    private ImportJobDto findReusableImportJobByFingerprint(String fingerprint) {
        var job = importJobMapper.selectOneByQuery(QueryWrapper.create()
                .select(IMPORT_JOB_ENTITY.ALL_COLUMNS)
                .from(IMPORT_JOB_ENTITY)
                .where(IMPORT_JOB_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(IMPORT_JOB_ENTITY.IMPORT_FINGERPRINT.eq(fingerprint))
                .and(IMPORT_JOB_ENTITY.STATUS.ne(ImportJobStatus.FAILED))
                .and(IMPORT_JOB_ENTITY.STATUS.ne(ImportJobStatus.CANCELED))
                .orderBy(IMPORT_JOB_ENTITY.CREATED_AT.desc())
                .limit(1));
        return job == null ? null : mapJob(job);
    }

    private int nextVersionNo(String documentId) {
        // 已存在文档在提交导入时持有文档行锁；新建文档行尚未提交，不会被其他事务并发分配版本号。
        var latest = documentVersionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DOCUMENT_VERSION_ENTITY.VERSION_NO)
                .from(DOCUMENT_VERSION_ENTITY)
                .where(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(documentId))
                .orderBy(DOCUMENT_VERSION_ENTITY.VERSION_NO.desc())
                .limit(1));
        return latest == null ? 1 : latest.getVersionNo() + 1;
    }

    private String currentVersionId(String documentId) {
        var document = documentMapper.selectOneById(documentId);
        return document == null ? null : document.getCurrentVersionId();
    }

    private ImportJobEntity loadJobForCommit(UUID jobId) {
        return requireJob(jobId);
    }

    private ImportJobEntity loadJobForRevision(UUID jobId) {
        var job = loadJobForCommit(jobId);
        if (job.getStatus() == ImportJobStatus.IMPORTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Imported jobs cannot be revised");
        }
        if (!(job.getStatus() == ImportJobStatus.READY || job.getStatus() == ImportJobStatus.REVIEW_REQUIRED)) {
            throw new ApiException(HttpStatus.CONFLICT, "Only READY or REVIEW_REQUIRED import jobs can be revised");
        }
        return job;
    }

    private JsonNode reviseNormalizedItem(UUID jobId, String arrayName, String keyField, String key, JsonNode patch) {
        if (patch == null || !patch.isObject()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Patch body must be a JSON object");
        }
        var job = loadJobForRevision(jobId);
        var root = readTree(job.getNormalizedObjectKey());
        var items = root.path(arrayName);
        if (!items.isArray()) {
            throw new ApiException(HttpStatus.CONFLICT, "Staged package has no " + arrayName + " array");
        }

        ObjectNode target = null;
        for (var item : items) {
            if (item instanceof ObjectNode objectNode && key.equals(objectNode.path(keyField).asText())) {
                target = objectNode;
                break;
            }
        }
        if (target == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Staged " + arrayName + " item not found");
        }

        var targetNode = target;
        patch.fields().forEachRemaining(entry -> targetNode.set(entry.getKey(), entry.getValue()));
        var documentPackage = readPackage(root.toString());
        var issues = validator.validate(documentPackage);
        replaceIssues(jobId, issues);
        job.setNormalizedObjectKey(toJson(root));
        job.setStatus(issues.stream().anyMatch(issue -> issue.severity() == ImportIssueSeverity.BLOCKING)
                ? ImportJobStatus.REVIEW_REQUIRED : ImportJobStatus.READY);
        job.setCurrentStage(ImportStage.REVIEWING);
        job.setErrorCode(null);
        job.setErrorMessage(null);
        importJobMapper.update(job);
        return root;
    }

    private void replaceIssues(UUID jobId, List<ImportIssueDto> issues) {
        for (var issue : importIssueMapper.selectListByQuery(QueryWrapper.create()
                .select(IMPORT_ISSUE_ENTITY.ALL_COLUMNS)
                .from(IMPORT_ISSUE_ENTITY)
                .where(IMPORT_ISSUE_ENTITY.JOB_ID.eq(id(jobId))))) {
            importIssueMapper.deleteById(issue.getId());
        }
        for (var issue : issues) {
            insertIssue(jobId, issue);
        }
    }

    private DocumentVersionDto getVersion(UUID versionId) {
        var version = documentVersionMapper.selectOneById(id(versionId));
        if (version == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Document version not found");
        }
        return new DocumentVersionDto(UUID.fromString(version.getId()), UUID.fromString(version.getDocumentId()), version.getVersionNo(), version.getStatus(), version.getSchemaVersion());
    }

    private DocumentPackage readPackage(String json) {
        try {
            return objectMapper.readValue(json, DocumentPackage.class);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Staged JSON package is invalid");
        }
    }

    private JsonNode snapshot(String json, String label) {
        if (isBlank(json) || "{}".equals(json)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import job has no " + label + " snapshot");
        }
        return readTree(json);
    }

    private ImportJobDto mapJob(ImportJobEntity job) {
        return new ImportJobDto(
                UUID.fromString(job.getId()),
                uuid(job.getTargetDocumentId()),
                job.getSourceType(),
                job.getStatus(),
                job.getCurrentStage(),
                job.getProgress(),
                readMap(job.getStatistics()),
                job.getErrorCode(),
                job.getErrorMessage());
    }

    private ImportIssueDto mapIssue(ImportIssueEntity issue) {
        return new ImportIssueDto(
                issue.getSeverity(),
                issue.getIssueCode(),
                issue.getMessage(),
                issue.getSourcePage(),
                issue.getSectionKey(),
                issue.getBlockKey(),
                issue.getCellRef());
    }

    private void insertIssue(UUID jobId, ImportIssueDto issue) {
        var entity = new ImportIssueEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setJobId(id(jobId));
        entity.setSeverity(issue.severity());
        entity.setIssueCode(issue.issueCode());
        entity.setMessage(issue.message());
        entity.setSourcePage(issue.sourcePage());
        entity.setSectionKey(issue.sectionKey());
        entity.setBlockKey(issue.blockKey());
        entity.setCellRef(issue.cellRef());
        entity.setDetails("{}");
        importIssueMapper.insertSelective(entity);
    }

    private void scheduleImportProcessing(
            UUID jobId,
            SourceType sourceType,
            byte[] fileBytes,
            String sourceFileName,
            String sourceSha256,
            Map<String, Object> statistics
    ) {
        var task = (Runnable) () -> processImportJob(jobId, sourceType, fileBytes, sourceFileName, sourceSha256, statistics);
        if (TransactionSynchronizationManager.isActualTransactionActive() && importJobWorker.isEnabled()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    importJobWorker.submit(jobId, task);
                }
            });
            return;
        }
        importJobWorker.submit(jobId, task);
    }

    private void updateImportStage(UUID jobId, ImportStage stage, int progress, Map<String, Object> statistics) {
        ensureNotCanceled(jobId);
        var job = requireJob(jobId);
        if (job.getStatus() == ImportJobStatus.CANCELED) {
            throw new ImportCanceledException();
        }
        job.setStatus(stage.activeJobStatus());
        job.setCurrentStage(stage);
        job.setProgress(progress);
        job.setStatistics(toJson(statistics));
        importJobMapper.update(job);
    }

    private void ensureNotCanceled(UUID jobId) {
        var job = job(jobId);
        if (job != null && job.getStatus() == ImportJobStatus.CANCELED) {
            throw new ImportCanceledException();
        }
    }

    private ImportJobEntity requireJob(UUID jobId) {
        var job = job(jobId);
        if (job == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Import job not found");
        }
        return job;
    }

    private ImportJobEntity job(UUID jobId) {
        return importJobMapper.selectOneByQuery(QueryWrapper.create()
                .select(IMPORT_JOB_ENTITY.ALL_COLUMNS)
                .from(IMPORT_JOB_ENTITY)
                .where(IMPORT_JOB_ENTITY.ID.eq(id(jobId))));
    }

    private SourceType detectSourceType(String fileName, byte[] bytes) {
        var normalizedName = Objects.toString(fileName, "").toLowerCase(Locale.ROOT);
        if (startsWith(bytes)) return SourceType.PDF;
        if (normalizedName.endsWith(".xlsx") || looksLikeZip(bytes)) return SourceType.EXCEL;
        if (normalizedName.endsWith(".md") || normalizedName.endsWith(".markdown")) return SourceType.MARKDOWN;
        if (normalizedName.endsWith(".json") || firstNonWhitespace(bytes) == '{' || firstNonWhitespace(bytes) == '[') return SourceType.JSON_PACKAGE;
        throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_SOURCE_FILE", "无法识别文件格式，请上传 PDF、Excel、Markdown 或 JSON 文档包。");
    }

    private static boolean startsWith(byte[] bytes) {
        var prefixBytes = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < prefixBytes.length) return false;
        for (var index = 0; index < prefixBytes.length; index++) {
            if (bytes[index] != prefixBytes[index]) return false;
        }
        return true;
    }

    private static char firstNonWhitespace(byte[] bytes) {
        for (var value : bytes) {
            var character = (char) value;
            if (!Character.isWhitespace(character)) return character;
        }
        return '\0';
    }
    private ParsedSource parseSource(SourceType sourceType, byte[] fileBytes, String sourceFileName, String sourceSha256) {
        if (sourceType == SourceType.EXCEL) {
            if (!looksLikeZip(fileBytes)) {
                return new ParsedSource(null, List.of(new ImportIssueDto(
                        ImportIssueSeverity.BLOCKING,
                        "EXCEL_MAGIC_INVALID",
                        "Uploaded file is not an XLSX/ZIP package",
                        null,
                        null,
                        null)), null);
            }
            var parsed = excelPackageService.parse(fileBytes);
            return new ParsedSource(parsed.documentPackage(), new ArrayList<>(parsed.issues()), null);
        }
        if (sourceType == SourceType.MARKDOWN) {
            var documentPackage = markdownPackageService.parse(fileBytes, sourceFileName, sourceSha256, converterVersion);
            return new ParsedSource(documentPackage, List.of(), null);
        }
        if (sourceType == SourceType.PDF) {
            var parsed = pdfPackageService.parse(fileBytes, sourceFileName, sourceSha256, converterVersion);
            return new ParsedSource(parsed.documentPackage(), new ArrayList<>(parsed.issues()), parsed.rawExtraction());
        }
        var json = new String(fileBytes, StandardCharsets.UTF_8);
        try {
            var documentPackage = objectMapper.readValue(json, DocumentPackage.class);
            return new ParsedSource(documentPackage, List.of(), null);
        } catch (JsonProcessingException exception) {
            return new ParsedSource(null, List.of(new ImportIssueDto(ImportIssueSeverity.BLOCKING, "JSON_INVALID", exception.getOriginalMessage(), null, null, null)), null);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Cannot read uploaded file");
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "Stored import snapshot is invalid");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize JSON", exception);
        }
    }

    private String toJsonOrNull(Object value) {
        return value == null ? null : toJson(value);
    }

    private static String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String value) {
        return value == null || value.isBlank() ? null : UUID.fromString(value);
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fa5]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String normalizedFileName(String fileName, String fallback) {
        if (isBlank(fileName)) {
            return fallback;
        }
        var slashIndex = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        var name = slashIndex >= 0 ? fileName.substring(slashIndex + 1) : fileName;
        var normalized = repairUtf8Mojibake(name.strip());
        return ".".equals(normalized) || "..".equals(normalized) || normalized.isBlank() ? fallback : normalized;
    }

    private static String repairUtf8Mojibake(String value) {
        var likelyLatin1Mojibake = value.chars().anyMatch(character -> character >= 0x0080 && character <= 0x00FF);
        if (!likelyLatin1Mojibake) {
            return value;
        }
        var bytes = new byte[value.length()];
        for (var index = 0; index < value.length(); index++) {
            var character = value.charAt(index);
            if (character > 0x00FF) {
                return value;
            }
            bytes[index] = (byte) character;
        }
        var repaired = new String(bytes, StandardCharsets.UTF_8);
        return repaired.indexOf('\uFFFD') >= 0 ? value : repaired;
    }

    private static String defaultFileName(SourceType sourceType) {
        return switch (sourceType) {
            case EXCEL -> "document-package.xlsx";
            case MARKDOWN -> "document.md";
            case PDF -> "document.pdf";
            default -> "document-package.json";
        };
    }

    private static boolean looksLikeZip(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4)
                || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8));
    }

    private record ParsedSource(DocumentPackage documentPackage, List<ImportIssueDto> issues, Object rawExtraction) {
    }

    private static class ImportCanceledException extends RuntimeException {
    }
}
