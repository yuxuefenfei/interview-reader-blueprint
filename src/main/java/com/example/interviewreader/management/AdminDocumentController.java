package com.example.interviewreader.management;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDocumentController {
    private final VersionRevisionService service;

    public AdminDocumentController(VersionRevisionService service) {
        this.service = service;
    }

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

    @PostMapping("/documents/{documentId}/versions/{versionId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        service.publish(documentId, versionId);
    }
}