package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.SearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reader/search")
@RequiredArgsConstructor
public class SearchController {
    private final DocumentQueryService service;


    @GetMapping
    public List<SearchHit> search(
            @RequestParam String q,
            @RequestParam(required = false) UUID documentId,
            @RequestParam(required = false) Integer limit
    ) {
        return service.search(q, documentId, limit);
    }
}
