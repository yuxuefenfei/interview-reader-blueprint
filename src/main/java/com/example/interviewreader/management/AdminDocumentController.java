package com.example.interviewreader.management;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    public ManagementDtos.AdminDocumentPage documents(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.documents(page, size);
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

    @GetMapping("/versions/{versionId}/editor")
    public ManagementDtos.EditableVersion editor(@PathVariable UUID versionId) {
        return service.editor(versionId);
    }

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