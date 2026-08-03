package com.example.interviewreader.document;

import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public image bytes are available only while their owning version is the published reader version. */
@RestController
@RequiredArgsConstructor
public class PublicImageAssetController {
    private final PublicImageAssetService service;

    /** Compatibility route for previously published HTML and cached links. */
    @GetMapping("/assets/versions/{versionId}/{assetKey}")
    public ResponseEntity<byte[]> image(@PathVariable UUID versionId, @PathVariable String assetKey) {
        return response(service.load(null, versionId, assetKey));
    }

    @GetMapping("/assets/documents/{documentId}/versions/{versionId}/{assetKey}")
    public ResponseEntity<byte[]> image(
            @PathVariable UUID documentId,
            @PathVariable UUID versionId,
            @PathVariable String assetKey
    ) {
        return response(service.load(documentId, versionId, assetKey));
    }

    private ResponseEntity<byte[]> response(PublicImageAssetService.StoredImage image) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.mimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag('"' + image.sha256() + '"')
                .body(image.bytes());
    }
}
