package com.example.interviewreader.management;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminDocumentController {
    private final VersionRevisionService service;
    private final DocumentLifecycleService lifecycleService;
    private final DocumentMetadataService metadataService;


    @GetMapping("/documents")
    public ManagementDtos.AdminDocumentPage documents(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return service.documents(query, page, size);
    }

    @GetMapping("/documents/{documentId}")
    public ManagementDtos.AdminDocumentSummary document(@PathVariable UUID documentId) {
        return service.document(documentId);
    }

    @GetMapping("/documents/{documentId}/metadata")
    public DocumentMetadataDtos.DocumentMetadata documentMetadata(@PathVariable UUID documentId) {
        return metadataService.get(documentId);
    }

    @PatchMapping("/documents/{documentId}/metadata")
    public DocumentMetadataDtos.DocumentMetadata updateDocumentMetadata(
            @PathVariable UUID documentId,
            @Valid @RequestBody DocumentMetadataDtos.UpdateDocumentMetadataRequest request
    ) {
        return metadataService.update(documentId, request);
    }

    @GetMapping("/documents/{documentId}/versions")
    public List<ManagementDtos.VersionSummary> versions(@PathVariable UUID documentId) {
        return service.versions(documentId);
    }

    @PostMapping("/documents/{documentId}/versions/{sourceVersionId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementDtos.VersionSummary createRevision(@PathVariable UUID documentId, @PathVariable UUID sourceVersionId) {
        return service.createRevision(documentId, sourceVersionId);
    }

    /** Small initial editor payload: document header and tree only, no block bodies. */
    @GetMapping("/versions/{versionId}/editor")
    public ManagementDtos.EditorSnapshot editor(@PathVariable UUID versionId) {
        return service.editorSnapshot(versionId);
    }

    @GetMapping("/versions/{versionId}/editor/nodes/{nodeId}/blocks")
    public ManagementDtos.NodeBlocksPage nodeBlocks(
            @PathVariable UUID versionId,
            @PathVariable UUID nodeId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return service.nodeBlocks(versionId, nodeId, cursor, limit);
    }

    @PostMapping("/versions/{versionId}/editor/nodes/{nodeId}/blocks")
    @ResponseStatus(HttpStatus.CREATED)
    public ManagementDtos.EditorBlock createBlock(
            @PathVariable UUID versionId,
            @PathVariable UUID nodeId,
            @Valid @RequestBody ManagementDtos.CreateBlockRequest request
    ) {
        return service.createBlock(versionId, nodeId, request);
    }

    @PatchMapping("/versions/{versionId}/editor/nodes/{nodeId}")
    public ManagementDtos.EditorSnapshot updateNode(
            @PathVariable UUID versionId,
            @PathVariable UUID nodeId,
            @Valid @RequestBody ManagementDtos.UpdateNodeRequest request
    ) {
        return service.updateNode(versionId, nodeId, request);
    }

    @PatchMapping("/versions/{versionId}/editor/structure")
    public ManagementDtos.EditorSnapshot updateStructure(
            @PathVariable UUID versionId,
            @Valid @RequestBody ManagementDtos.StructureUpdateRequest request
    ) {
        return service.updateStructure(versionId, request);
    }

    @PatchMapping("/versions/{versionId}/editor/blocks/{blockId}")
    public ManagementDtos.EditorBlock updateBlock(
            @PathVariable UUID versionId,
            @PathVariable UUID blockId,
            @Valid @RequestBody ManagementDtos.UpdateBlockRequest request
    ) {
        return service.updateBlock(versionId, blockId, request);
    }

    @PostMapping("/versions/{versionId}/editor/blocks/{blockId}/image")
    public ManagementDtos.ImageBlockUploadResult uploadBlockImage(
            @PathVariable UUID versionId,
            @PathVariable UUID blockId,
            @RequestParam MultipartFile file,
            @RequestParam long draftRevision,
            @RequestParam(required = false) String alt,
            @RequestParam(defaultValue = "false") boolean decorative,
            @RequestParam(required = false) String caption
    ) {
        return service.replaceBlockImage(versionId, blockId, draftRevision, file, alt, decorative, caption);
    }

    @GetMapping("/versions/{versionId}/editor/assets/{assetKey}")
    public ResponseEntity<byte[]> draftImage(@PathVariable UUID versionId, @PathVariable String assetKey) {
        var image = service.draftImage(versionId, assetKey);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.noStore())
                .eTag('"' + image.sha256() + '"')
                .body(image.bytes());
    }

    @DeleteMapping("/versions/{versionId}/editor/blocks/{blockId}")
    public ManagementDtos.BlockMutationResult deleteBlock(
            @PathVariable UUID versionId,
            @PathVariable UUID blockId,
            @RequestParam long draftRevision
    ) {
        return service.deleteBlock(versionId, blockId, draftRevision);
    }

    @PostMapping("/versions/{versionId}/editor/blocks/cleanup-empty")
    public ManagementDtos.BlockMutationResult cleanupEmptyBlocks(
            @PathVariable UUID versionId,
            @Valid @RequestBody ManagementDtos.BlockCleanupRequest request
    ) {
        return service.cleanupEmptyBlocks(versionId, request);
    }
    /** Kept temporarily for external clients that still save a complete package. */
    @PutMapping("/versions/{versionId}/editor")
    public ManagementDtos.EditableVersion save(@PathVariable UUID versionId, @Valid @RequestBody ManagementDtos.SaveDraftRequest request) {
        return service.save(versionId, request);
    }

    @DeleteMapping("/versions/{versionId}/editor")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDraft(@PathVariable UUID versionId) {
        service.deleteDraft(versionId);
    }


    @PostMapping("/documents/{documentId}/take-down")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void takeDown(@PathVariable UUID documentId) {
        lifecycleService.takeDown(documentId);
    }

    @PostMapping("/documents/{documentId}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void restore(@PathVariable UUID documentId) {
        lifecycleService.restore(documentId);
    }

    @PostMapping("/documents/{documentId}/deletion")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ManagementDtos.DeletionJobSummary deleteDocument(
            @PathVariable UUID documentId,
            @RequestBody ManagementDtos.DeleteDocumentRequest request) {
        return lifecycleService.requestDeletion(documentId, request);
    }

    @GetMapping("/document-deletions/{jobId}")
    public ManagementDtos.DeletionJobSummary deletionJob(@PathVariable UUID jobId) {
        return lifecycleService.job(jobId);
    }

    @PostMapping("/document-deletions/{jobId}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ManagementDtos.DeletionJobSummary retryDeletion(@PathVariable UUID jobId) {
        return lifecycleService.retry(jobId);
    }
    @PostMapping("/documents/{documentId}/versions/{versionId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        service.publish(documentId, versionId);
    }
}
