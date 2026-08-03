package com.example.interviewreader.document;

import com.example.interviewreader.common.ApiException;
import com.example.interviewreader.common.AppConstants;
import com.example.interviewreader.importpkg.SourceFileStorage;
import com.example.interviewreader.persistence.mapper.AssetMapper;
import com.mybatisflex.core.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

import static com.example.interviewreader.persistence.entity.table.AssetEntityTableDef.ASSET_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentEntityTableDef.DOCUMENT_ENTITY;
import static com.example.interviewreader.persistence.entity.table.DocumentVersionEntityTableDef.DOCUMENT_VERSION_ENTITY;

@Service
@RequiredArgsConstructor
public class PublicImageAssetService {
    private static final String LOCAL_USER_ID = AppConstants.LOCAL_USER_ID.toString();

    private final AssetMapper assetMapper;
    private final SourceFileStorage storage;

    public StoredImage load(UUID documentId, UUID versionId, String assetKey) {
        var query = QueryWrapper.create()
                .select(ASSET_ENTITY.ALL_COLUMNS)
                .from(ASSET_ENTITY)
                .innerJoin(DOCUMENT_VERSION_ENTITY).on(ASSET_ENTITY.VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID))
                .innerJoin(DOCUMENT_ENTITY).on(DOCUMENT_VERSION_ENTITY.DOCUMENT_ID.eq(DOCUMENT_ENTITY.ID))
                .where(ASSET_ENTITY.VERSION_ID.eq(versionId.toString()))
                .and(ASSET_ENTITY.ASSET_KEY.eq(assetKey))
                .and(DOCUMENT_VERSION_ENTITY.STATUS.eq(DocumentVersionStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.STATUS.eq(DocumentStatus.PUBLISHED))
                .and(DOCUMENT_ENTITY.OWNER_ID.eq(LOCAL_USER_ID))
                .and(DOCUMENT_ENTITY.CURRENT_VERSION_ID.eq(DOCUMENT_VERSION_ENTITY.ID));
        if (documentId != null) {
            query.and(DOCUMENT_ENTITY.ID.eq(documentId.toString()));
        }
        var asset = assetMapper.selectOneByQuery(query);
        if (asset == null || asset.getMimeType() == null || !asset.getMimeType().startsWith("image/")) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "图片不存在。");
        }
        var source = storage.load(asset.getObjectKey());
        return new StoredImage(asset.getMimeType(), asset.getSha256(), source.bytes());
    }

    public record StoredImage(String mimeType, String sha256, byte[] bytes) {
    }
}
