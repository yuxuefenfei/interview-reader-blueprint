package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.NodeContent;
import com.example.interviewreader.document.DocumentDtos.TocNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reader/versions")
public class ReaderController {
    private final DocumentQueryService service;
    private final ObjectMapper objectMapper;

    public ReaderController(DocumentQueryService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/{versionId}/toc")
    public ResponseEntity<List<TocNode>> toc(
            @PathVariable UUID versionId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        var toc = service.toc(versionId);
        var etag = etag(toc);
        if (matches(ifNoneMatch, etag)) {
            return notModified(etag);
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(toc);
    }

    @GetMapping("/{versionId}/nodes/{nodeId}/content")
    public ResponseEntity<NodeContent> content(
            @PathVariable UUID versionId,
            @PathVariable UUID nodeId,
            @RequestParam(required = false) Integer afterSeq,
            @RequestParam(required = false) Integer limit,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        var content = service.content(versionId, nodeId, afterSeq, limit);
        var etag = etag(content);
        if (matches(ifNoneMatch, etag)) {
            return notModified(etag);
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(content);
    }

    private <T> ResponseEntity<T> notModified(String etag) {
        return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .build();
    }

    private String etag(Object body) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(body));
            return '"' + HexFormat.of().formatHex(digest) + '"';
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to build response ETag", exception);
        }
    }

    private boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (var candidate : ifNoneMatch.split(",")) {
            var normalized = candidate.trim();
            if ("*".equals(normalized) || etag.equals(normalized) || etag.equals(normalized.replaceFirst("^W/", ""))) {
                return true;
            }
        }
        return false;
    }
}
