package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.NodeContent;
import com.example.interviewreader.document.DocumentDtos.TocNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/reader/versions")
@RequiredArgsConstructor
public class ReaderController {
    private final DocumentQueryService service;


    @GetMapping("/{versionId}/toc")
    public ResponseEntity<List<TocNode>> toc(
            @PathVariable UUID versionId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        var etag = etag("toc", versionId);
        if (matches(ifNoneMatch, etag)) {
            service.ensureReadableVersion(versionId);
            return notModified(etag);
        }
        var toc = service.toc(versionId);
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
        var normalizedAfterSeq = Math.max(afterSeq == null ? 0 : afterSeq, 0);
        var normalizedLimit = Math.clamp(limit == null ? 50 : limit, 1, 100);
        var etag = etag("content", versionId, nodeId, normalizedAfterSeq, normalizedLimit);
        if (matches(ifNoneMatch, etag)) {
            service.ensureReadableNode(versionId, nodeId);
            return notModified(etag);
        }
        var content = service.content(versionId, nodeId, afterSeq, limit);
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

    private String etag(Object... components) {
        try {
            var value = java.util.Arrays.stream(components)
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.joining(":"));
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return '"' + HexFormat.of().formatHex(digest) + '"';
        } catch (NoSuchAlgorithmException exception) {
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
