package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.DocumentSummary;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
