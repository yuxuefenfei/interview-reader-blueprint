package cn.wangwenzhu.ireader.document;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

/**
 * 只有当图片所属版本为阅读端当前发布版本时，才允许公开访问图片内容。
 */
@RestController
@RequiredArgsConstructor
public class PublicImageAssetController {
    private final PublicImageAssetService service;

    /**
     * 为此前发布的 HTML 和已缓存的链接提供兼容路由。
     */
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
