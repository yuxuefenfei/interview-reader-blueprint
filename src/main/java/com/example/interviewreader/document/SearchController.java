package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.SearchHit;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reader/search")
public class SearchController {
    private final DocumentQueryService service;

    public SearchController(DocumentQueryService service) {
        this.service = service;
    }

    @GetMapping
    public List<SearchHit> search(
            @RequestParam String q,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) Integer limit
    ) {
        return service.search(q, documentId, limit);
    }
}
