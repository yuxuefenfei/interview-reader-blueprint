package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.DocumentSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentQueryService service;

    public DocumentController(DocumentQueryService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String query) {
        return Map.of("items", service.listDocuments(query), "nextCursor", "");
    }

    @GetMapping("/{documentId}")
    public DocumentSummary get(@PathVariable UUID documentId) {
        return service.getDocument(documentId);
    }

    @PostMapping("/{documentId}/versions/{versionId}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(@PathVariable UUID documentId, @PathVariable UUID versionId) {
        service.publish(documentId, versionId);
    }
}
