package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.importpkg.SourceFileStorage;
import com.example.interviewreader.persistence.entity.table.AssetEntityTableDef;
import com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef;
import com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.example.interviewreader.persistence.mapper.DocumentMapper;
import com.example.interviewreader.persistence.mapper.DocumentVersionMapper;
import com.mybatisflex.core.query.QueryWrapper;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public image bytes are available only while their owning version is the published reader version. */
@RestController
@RequiredArgsConstructor
public class PublicImageAssetController {
    private final DocumentVersionMapper versionMapper;
    private final DocumentMapper documentMapper;
    private final AssetMapper assetMapper;
    private final SourceFileStorage storage;

    @GetMapping("/assets/versions/{versionId}/{assetKey}")
    public ResponseEntity<byte[]> image(@PathVariable UUID versionId, @PathVariable String assetKey) {
        var version = versionMapper.selectOneByQuery(QueryWrapper.create()
                .select(DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY.ALL_COLUMNS)
                .from(DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY)
                .where(DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY.ID.eq(versionId.toString())));
        if (version == null || version.getStatus() != DocumentVersionStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "图片不存在。");
        }
        var document = documentMapper.selectOneById(version.getDocumentId());
        if (document == null || document.getStatus() != DocumentStatus.PUBLISHED) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "图片不存在。");
        }
        var asset = assetMapper.selectOneByQuery(QueryWrapper.create()
                .select(AssetEntityTableDef.ASSET_ENTITY.ALL_COLUMNS)
                .from(AssetEntityTableDef.ASSET_ENTITY)
                .where(AssetEntityTableDef.ASSET_ENTITY.VERSION_ID.eq(version.getId()))
                .and(AssetEntityTableDef.ASSET_ENTITY.ASSET_KEY.eq(assetKey)));
        if (asset == null || asset.getMimeType() == null || !asset.getMimeType().startsWith("image/")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "图片不存在。");
        }
        var source = storage.load(asset.getObjectKey());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(asset.getMimeType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .eTag('"' + asset.getSha256() + '"')
                .body(source.bytes());
    }
}
