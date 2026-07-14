package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.DocumentSummary;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/reader/documents")
public class DocumentController {
    private final DocumentQueryService service;

    public DocumentController(DocumentQueryService service) {
        this.service = service;
    }

    @GetMapping
    public DocumentDtos.DocumentPage list(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit
    ) {
        return service.listDocuments(query, cursor, limit);
    }

    @GetMapping("/{documentId}")
    public DocumentSummary get(@PathVariable UUID documentId) {
        return service.getDocument(documentId);
    }


}
