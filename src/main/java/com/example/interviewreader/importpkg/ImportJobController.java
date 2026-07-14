package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/import-jobs")
public class ImportJobController {
    private final ImportPackageService service;

    public ImportJobController(ImportPackageService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobDto create(
            @RequestParam(required = false) String sourceType,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) UUID targetDocumentId
    ) {
        return service.createImportJob(sourceType, file, targetDocumentId);
    }

    @GetMapping("/{jobId}")
    public ImportJobDto get(@PathVariable UUID jobId) {
        return service.getImportJob(jobId);
    }

    @GetMapping("/{jobId}/issues")
    public List<ImportIssueDto> issues(@PathVariable UUID jobId) {
        return service.listIssues(jobId);
    }

    @GetMapping("/{jobId}/raw-extraction")
    public JsonNode rawExtraction(@PathVariable UUID jobId) {
        return service.rawExtraction(jobId);
    }

    @GetMapping("/{jobId}/source-file")
    public ResponseEntity<byte[]> sourceFile(@PathVariable UUID jobId) {
        var source = service.sourceFile(jobId);
        var mediaType = source.fileName().toLowerCase().endsWith(".pdf")
                ? MediaType.APPLICATION_PDF
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(source.fileName())
                        .build()
                        .toString())
                .body(source.bytes());
    }

    @GetMapping("/{jobId}/normalized-package")
    public JsonNode normalizedPackage(@PathVariable UUID jobId) {
        return service.normalizedPackage(jobId);
    }

    @PatchMapping("/{jobId}/normalized-package/sections/{sectionKey}")
    public JsonNode reviseSection(
            @PathVariable UUID jobId,
            @PathVariable String sectionKey,
            @RequestBody JsonNode patch
    ) {
        return service.reviseSection(jobId, sectionKey, patch);
    }

    @PatchMapping("/{jobId}/normalized-package/blocks/{blockKey}")
    public JsonNode reviseBlock(
            @PathVariable UUID jobId,
            @PathVariable String blockKey,
            @RequestBody JsonNode patch
    ) {
        return service.reviseBlock(jobId, blockKey, patch);
    }

    @PostMapping("/{jobId}/commit")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentVersionDto commit(@PathVariable UUID jobId) {
        return service.commit(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public ImportJobDto cancel(@PathVariable UUID jobId) {
        return service.cancel(jobId);
    }
}
