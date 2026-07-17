package com.example.interviewreader.importpkg;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/import-jobs")
@RequiredArgsConstructor
public class ImportJobController {
    private final ImportPackageService service;


    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ImportJobDto create(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) UUID targetDocumentId
    ) {
        return service.createImportJob(file, targetDocumentId);
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
