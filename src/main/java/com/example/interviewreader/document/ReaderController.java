package com.example.interviewreader.document;

import com.example.interviewreader.document.DocumentDtos.NodeContent;
import com.example.interviewreader.document.DocumentDtos.TocNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/versions")
public class ReaderController {
    private final DocumentQueryService service;

    public ReaderController(DocumentQueryService service) {
        this.service = service;
    }

    @GetMapping("/{versionId}/toc")
    public ResponseEntity<List<TocNode>> toc(@PathVariable UUID versionId) {
        var toc = service.toc(versionId);
        var etag = '"' + Integer.toHexString(toc.toString().getBytes(StandardCharsets.UTF_8).length) + '"';
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(CacheControl.noCache())
                .body(toc);
    }

    @GetMapping("/{versionId}/nodes/{nodeId}/content")
    public NodeContent content(
            @PathVariable UUID versionId,
            @PathVariable UUID nodeId,
            @RequestParam(required = false) Integer afterSeq,
            @RequestParam(required = false) Integer limit
    ) {
        return service.content(versionId, nodeId, afterSeq, limit);
    }
}
