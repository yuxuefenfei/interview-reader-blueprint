// 此文件由 @hey-api/openapi-ts 自动生成

export type ClientOptions = {
    baseUrl: `${string}://${string}/api` | (string & {});
};

export type JsonValue = unknown;

export type JsonObject = {
    [key: string]: unknown;
};

export type Uuid = string;

export type NullableUuid = string | null;

export type SourceType = 'PDF' | 'EXCEL' | 'JSON_PACKAGE' | 'MARKDOWN' | 'MANUAL';

export type NodeType = 'PART' | 'CHAPTER' | 'SECTION' | 'SUBSECTION' | 'QUESTION' | 'APPENDIX' | 'OTHER';

export type BlockType = 'paragraph' | 'heading_note' | 'unordered_list' | 'ordered_list' | 'code' | 'table' | 'quote' | 'callout' | 'formula' | 'image' | 'divider' | 'table_snapshot';

export type SemanticRole = 'QUESTION' | 'ANSWER' | 'EXPLANATION' | 'CONCLUSION' | 'INTRODUCTION' | 'DIRECTORY' | 'PRINCIPLE' | 'PRACTICE' | 'PITFALL' | 'FOLLOW_UP';

export type VersionStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';

export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'OFFLINE' | 'DELETING' | 'DELETE_FAILED';

export type DeletionJobStatus = 'QUEUED' | 'RUNNING' | 'FAILED' | 'COMPLETED';

export type DeletionStage = 'QUEUED' | 'CLIENT_SYNC_MARKED' | 'DELETING_FILES' | 'DELETING_DATA' | 'COMPLETED' | 'FAILED';

export type ImportStatus = 'UPLOADED' | 'PREFLIGHT' | 'EXTRACTING' | 'NORMALIZING' | 'VALIDATING' | 'READY' | 'REVIEW_REQUIRED' | 'IMPORTED' | 'FAILED' | 'CANCELED';

export type ImportStage = 'UPLOADED' | 'PREFLIGHT' | 'EXTRACTING' | 'NORMALIZING' | 'VALIDATING' | 'READY' | 'REVIEW_REQUIRED' | 'REVIEWING' | 'FAILED' | 'CANCELED' | 'COMMITTED' | 'DRAFT_DISCARDED';

export type MasteryState = 'UNKNOWN' | 'HARD' | 'FUZZY' | 'KNOWN';

export type Problem = {
    type: string;
    title: string;
    status: number;
    detail: string;
    instance?: string;
    code: string;
    error: string;
    traceId: string;
    fieldErrors?: {
        [key: string]: string;
    };
};

export type Health = {
    status: 'UP';
};

export type LoginRequest = {
    username: string;
    password: string;
};

export type AuthSession = {
    authenticated: boolean;
    username: string | null;
};

export type DocumentSummary = {
    id: Uuid;
    code: string;
    title: string;
    description: string | null;
    currentVersionId: NullableUuid;
    /**
     * 当前阅读位置占文档可翻页章节序列的比例。
     */
    progressRatio: number;
};

export type DocumentPage = {
    items: Array<DocumentSummary>;
    nextCursor: string | null;
};

export type TocNode = {
    id: Uuid;
    parentId: NullableUuid;
    title: string;
    level: number;
    nodeType: NodeType;
    semanticRole: SemanticRole | null;
    anchor: string;
    sourcePageStart: number | null;
    children: Array<TocNode>;
};

export type SourceBbox = {
    page?: number | null;
    x?: number | null;
    y?: number | null;
    width?: number | null;
    height?: number | null;
    [key: string]: unknown;
};

export type ContentBlock = {
    id: Uuid;
    blockKey: string;
    seq: number;
    blockType: BlockType;
    payload: JsonObject;
    plainText: string;
    sourcePage: number | null;
    sourceBbox: SourceBbox | null;
    confidence: number | null;
};

export type NodeContent = {
    node: TocNode;
    blocks: Array<ContentBlock>;
    nextAfterSeq: number | null;
};

export type SearchHit = {
    documentId: Uuid;
    documentTitle: string;
    versionId: Uuid;
    nodeId: Uuid;
    blockId: Uuid;
    title: string;
    sectionPath: Array<string>;
    snippet: string;
    score: number;
};

export type ReadingProgress = {
    versionId: Uuid;
    sectionId: NullableUuid;
    blockId: NullableUuid;
    charOffset: number;
    /**
     * 内容块顶部相对阅读视口顶部的像素偏移；内容块顶部已滚出视口时为负数。
     */
    blockViewportOffset: number;
    /**
     * 当前章节内的滚动进度比例，用于恢复阅读位置。
     */
    progressRatio: number;
    clientUpdatedAt: string | null;
    deviceId: string | null;
    revision: number;
};

export type BookmarkRequest = {
    documentId: Uuid;
    versionId: Uuid;
    sectionId: Uuid;
    blockId: Uuid;
    title: string | null;
};

export type Bookmark = {
    id: Uuid;
    documentId: Uuid;
    versionId: Uuid;
    sectionId: Uuid;
    blockId: Uuid;
    title: string | null;
    createdAt: string;
};

export type NoteRequest = {
    documentId: Uuid;
    versionId: Uuid;
    sectionId: Uuid;
    blockId: NullableUuid;
    selectedText: string | null;
    body: string;
};

export type Note = {
    id: Uuid;
    documentId: Uuid;
    versionId: Uuid;
    sectionId: Uuid;
    blockId: NullableUuid;
    selectedText: string | null;
    body: string;
    createdAt: string;
    updatedAt: string;
};

export type ReviewStateRequest = {
    documentId: Uuid;
    mastery: MasteryState;
};

export type ReviewState = {
    id: Uuid;
    documentId: Uuid;
    nodeId: Uuid;
    mastery: MasteryState;
    dueAt: string | null;
    intervalDays: number | null;
    repetitions: number;
    updatedAt: string;
};

export type ReviewQueueItem = {
    documentId: Uuid;
    versionId: Uuid;
    nodeId: Uuid;
    title: string;
    sectionPath: Array<string>;
    nodeType: NodeType;
    semanticRole: SemanticRole | null;
    sourcePageStart: number | null;
    mastery: MasteryState;
    dueAt: string | null;
    intervalDays: number | null;
    repetitions: number;
};

export type DeleteDocumentRequest = {
    confirmationTitle: string;
};

export type DeletionJob = {
    id: Uuid;
    documentId: Uuid;
    status: DeletionJobStatus;
    currentStage: DeletionStage;
    attemptCount: number;
    errorCode: string | null;
    errorMessage: string | null;
    requestedAt: string;
    startedAt: string | null;
    completedAt: string | null;
    updatedAt: string;
};

export type DeletedDocumentTombstone = {
    documentId: Uuid;
    deletedAt: string;
};

export type DocumentMetadata = {
    documentId: Uuid;
    readonly code: string;
    title: string;
    description: string | null;
    tags: Array<string>;
    metadataRevision: number;
    duplicateTitleCount: number;
};

export type UpdateDocumentMetadataRequest = {
    metadataRevision: number;
    title: string;
    description: string | null;
    tags: Array<string>;
};

export type AdminDocumentSummary = {
    id: Uuid;
    code: string;
    title: string;
    status: DocumentStatus;
    currentVersionId: NullableUuid;
    versionCount: number;
    draftCount: number;
    updatedAt: string;
    deletionJob: DeletionJob | null;
};

export type AdminDocumentPage = {
    items: Array<AdminDocumentSummary>;
    page: number;
    size: number;
    hasNext: boolean;
};

export type VersionSummary = {
    id: Uuid;
    versionNo: number;
    parentVersionId: NullableUuid;
    parentVersionNo: number | null;
    originImportJobId: NullableUuid;
    sourceType: SourceType;
    sourceFileName: string | null;
    status: VersionStatus;
    draftRevision: number;
    publishedAt: string | null;
    createdAt: string;
};

export type EditorDocument = {
    id: Uuid;
    code: string;
    title: string;
    description: string | null;
    language: string;
};

export type EditorNode = {
    id: Uuid;
    parentId: NullableUuid;
    nodeKey: string;
    nodeType: NodeType;
    semanticRole: SemanticRole | null;
    title: string;
    level: number;
    sortOrder: number;
    anchor: string;
    sourcePageStart: number | null;
    sourcePageEnd: number | null;
};

export type EditorSnapshot = {
    version: VersionSummary;
    document: EditorDocument;
    nodes: Array<EditorNode>;
};

export type EditorBlock = {
    id: Uuid;
    blockKey: string;
    seq: number;
    blockType: BlockType;
    payload: JsonObject;
    plainText: string;
    language: string | null;
    sourcePage: number | null;
    sourceBbox: SourceBbox | null;
    confidence: number | null;
};

export type NodeBlocksPage = {
    items: Array<EditorBlock>;
    nextCursor: string | null;
};

export type BlockMutationResult = {
    draftRevision: number;
    removedCount: number;
};

export type ImageBlockUploadResult = {
    block: EditorBlock;
    draftRevision: number;
};

export type DocumentInfo = {
    documentKey: string;
    title: string;
    description: string | null;
    language: string;
    tags: Array<string>;
};

export type VersionInfo = {
    versionKey: string;
    sourceType: SourceType;
    sourceFileName: string | null;
    sourceSha256: string | null;
    converterVersion: string | null;
    metadata: JsonObject;
};

export type DocumentPackageSection = {
    sectionKey: string;
    parentSectionKey: string | null;
    level: number;
    nodeType: NodeType;
    semanticRole: SemanticRole | null;
    title: string;
    sortOrder: number;
    /**
     * 由服务端生成的稳定内部标识；导入时忽略外部传入的值。
     */
    anchor: string;
    sourcePageStart: number | null;
    sourcePageEnd: number | null;
    sourceBbox: SourceBbox | null;
    contentHash: string | null;
};

export type DocumentPackageBlock = {
    blockKey: string;
    sectionKey: string;
    seq: number;
    blockType: BlockType;
    payload: JsonObject;
    plainText: string;
    language: string | null;
    sourcePage: number | null;
    sourceBbox: SourceBbox | null;
    confidence: number | null;
    contentHash: string | null;
};

export type AssetInfo = {
    assetKey: string;
    path: string;
    mimeType: string;
    sha256: string;
    alt: string | null;
};

export type DocumentPackage = {
    schemaVersion: '1.0';
    document: DocumentInfo;
    version: VersionInfo;
    sections: Array<DocumentPackageSection>;
    blocks: Array<DocumentPackageBlock>;
    assets: Array<AssetInfo>;
};

export type ImportJob = {
    id: Uuid;
    targetDocumentId: NullableUuid;
    sourceType: SourceType;
    status: ImportStatus;
    currentStage: ImportStage | null;
    progress: number;
    statistics: JsonObject;
    errorCode: string | null;
    errorMessage: string | null;
};

export type ImportResolution = 'CREATE_NEW' | 'IMPORT_AS_NEW_VERSION';

export type ImportIssueSeverity = 'BLOCKING' | 'WARNING';

export type ExistingDocumentMatch = {
    id: Uuid;
    code: string;
    title: string;
    status: DocumentStatus;
};

export type ImportDocumentPreview = {
    readonly documentKey: string;
    title: string;
    description: string | null;
    tags: Array<string>;
    editable: boolean;
    matchingDocument: ExistingDocumentMatch | null;
    readonly suggestedDocumentKey: string | null;
    duplicateTitleCount: number;
};

export type UpdateImportDocumentMetadataRequest = {
    title: string;
    description: string | null;
    tags: Array<string>;
};

export type CommitImportRequest = {
    resolution: ImportResolution;
};

export type ImportIssue = {
    severity: ImportIssueSeverity;
    issueCode: string;
    message: string;
    sourcePage: number | null;
    sectionKey: string | null;
    blockKey: string | null;
    cellRef: string | null;
};

export type DocumentVersion = {
    id: Uuid;
    documentId: Uuid;
    versionNo: number;
    status: VersionStatus;
    schemaVersion: string;
};

export type UpdateNodeRequest = {
    draftRevision: number;
    title: string;
    nodeType: NodeType;
    semanticRole: SemanticRole | null;
};

export type StructureNode = {
    id: Uuid;
    parentId: NullableUuid;
    sortOrder: number;
};

export type StructureUpdateRequest = {
    draftRevision: number;
    nodes: Array<StructureNode>;
};

export type BlockWriteRequest = {
    draftRevision: number;
    blockType: BlockType;
    payload: JsonObject | null;
    plainText: string | null;
    language: string | null;
};

export type BlockCleanupRequest = {
    draftRevision: number;
};

export type ExportRequest = {
    documentId: Uuid;
    versionId: Uuid;
    format: 'JSON_PACKAGE' | 'EXCEL' | 'MARKDOWN' | 'STATIC_HTML';
};

export type DocumentMetadataWritable = {
    documentId: Uuid;
    title: string;
    description: string | null;
    tags: Array<string>;
    metadataRevision: number;
    duplicateTitleCount: number;
};

export type ImportDocumentPreviewWritable = {
    title: string;
    description: string | null;
    tags: Array<string>;
    editable: boolean;
    matchingDocument: ExistingDocumentMatch | null;
    duplicateTitleCount: number;
};

export type DocumentId = string;

export type VersionId = string;

export type NodeId = string;

export type JobId = string;

export type GetHealthData = {
    body?: never;
    path?: never;
    query?: never;
    url: '/health';
};

export type GetHealthErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetHealthError = GetHealthErrors[keyof GetHealthErrors];

export type GetHealthResponses = {
    /**
     * 请求成功
     */
    200: Health;
};

export type GetHealthResponse = GetHealthResponses[keyof GetHealthResponses];

export type PostAuthLoginData = {
    body: LoginRequest;
    path?: never;
    query?: never;
    url: '/auth/login';
};

export type PostAuthLoginErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    401: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    403: Problem;
    /**
     * 登录尝试超过频率限制；Retry-After 返回以秒为单位的等待时间
     */
    429: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAuthLoginError = PostAuthLoginErrors[keyof PostAuthLoginErrors];

export type PostAuthLoginResponses = {
    /**
     * 身份认证状态
     */
    200: AuthSession;
};

export type PostAuthLoginResponse = PostAuthLoginResponses[keyof PostAuthLoginResponses];

export type GetAuthSessionData = {
    body?: never;
    path?: never;
    query?: never;
    url: '/auth/session';
};

export type GetAuthSessionErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAuthSessionError = GetAuthSessionErrors[keyof GetAuthSessionErrors];

export type GetAuthSessionResponses = {
    /**
     * 身份认证状态
     */
    200: AuthSession;
};

export type GetAuthSessionResponse = GetAuthSessionResponses[keyof GetAuthSessionResponses];

export type PostAuthLogoutData = {
    body?: never;
    path?: never;
    query?: never;
    url: '/auth/logout';
};

export type PostAuthLogoutErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAuthLogoutError = PostAuthLogoutErrors[keyof PostAuthLogoutErrors];

export type PostAuthLogoutResponses = {
    /**
     * 身份认证状态
     */
    200: AuthSession;
};

export type PostAuthLogoutResponse = PostAuthLogoutResponses[keyof PostAuthLogoutResponses];

export type GetReaderDocumentsData = {
    body?: never;
    path?: never;
    query?: {
        query?: string;
        cursor?: string;
        limit?: number;
    };
    url: '/reader/documents';
};

export type GetReaderDocumentsErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderDocumentsError = GetReaderDocumentsErrors[keyof GetReaderDocumentsErrors];

export type GetReaderDocumentsResponses = {
    /**
     * 请求成功
     */
    200: DocumentPage;
};

export type GetReaderDocumentsResponse = GetReaderDocumentsResponses[keyof GetReaderDocumentsResponses];

export type GetReaderDocumentDeletionsData = {
    body?: never;
    path?: never;
    query?: never;
    url: '/reader/document-deletions';
};

export type GetReaderDocumentDeletionsErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderDocumentDeletionsError = GetReaderDocumentDeletionsErrors[keyof GetReaderDocumentDeletionsErrors];

export type GetReaderDocumentDeletionsResponses = {
    /**
     * 最近的文档删除标记
     */
    200: Array<DeletedDocumentTombstone>;
};

export type GetReaderDocumentDeletionsResponse = GetReaderDocumentDeletionsResponses[keyof GetReaderDocumentDeletionsResponses];

export type GetReaderDocumentsByDocumentIdData = {
    body?: never;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/reader/documents/{documentId}';
};

export type GetReaderDocumentsByDocumentIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    404: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderDocumentsByDocumentIdError = GetReaderDocumentsByDocumentIdErrors[keyof GetReaderDocumentsByDocumentIdErrors];

export type GetReaderDocumentsByDocumentIdResponses = {
    /**
     * 请求成功
     */
    200: DocumentSummary;
};

export type GetReaderDocumentsByDocumentIdResponse = GetReaderDocumentsByDocumentIdResponses[keyof GetReaderDocumentsByDocumentIdResponses];

export type GetReaderVersionsByVersionIdTocData = {
    body?: never;
    path: {
        versionId: string;
    };
    query?: never;
    url: '/reader/versions/{versionId}/toc';
};

export type GetReaderVersionsByVersionIdTocErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderVersionsByVersionIdTocError = GetReaderVersionsByVersionIdTocErrors[keyof GetReaderVersionsByVersionIdTocErrors];

export type GetReaderVersionsByVersionIdTocResponses = {
    /**
     * 请求成功
     */
    200: Array<TocNode>;
};

export type GetReaderVersionsByVersionIdTocResponse = GetReaderVersionsByVersionIdTocResponses[keyof GetReaderVersionsByVersionIdTocResponses];

export type GetReaderVersionsByVersionIdNodesByNodeIdContentData = {
    body?: never;
    path: {
        versionId: string;
        nodeId: string;
    };
    query?: {
        afterSeq?: number;
        limit?: number;
    };
    url: '/reader/versions/{versionId}/nodes/{nodeId}/content';
};

export type GetReaderVersionsByVersionIdNodesByNodeIdContentErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderVersionsByVersionIdNodesByNodeIdContentError = GetReaderVersionsByVersionIdNodesByNodeIdContentErrors[keyof GetReaderVersionsByVersionIdNodesByNodeIdContentErrors];

export type GetReaderVersionsByVersionIdNodesByNodeIdContentResponses = {
    /**
     * 请求成功
     */
    200: NodeContent;
};

export type GetReaderVersionsByVersionIdNodesByNodeIdContentResponse = GetReaderVersionsByVersionIdNodesByNodeIdContentResponses[keyof GetReaderVersionsByVersionIdNodesByNodeIdContentResponses];

export type GetReaderSearchData = {
    body?: never;
    path?: never;
    query: {
        q: string;
        documentId?: string;
        limit?: number;
    };
    url: '/reader/search';
};

export type GetReaderSearchErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderSearchError = GetReaderSearchErrors[keyof GetReaderSearchErrors];

export type GetReaderSearchResponses = {
    /**
     * 请求成功
     */
    200: Array<SearchHit>;
};

export type GetReaderSearchResponse = GetReaderSearchResponses[keyof GetReaderSearchResponses];

export type GetReaderReadingProgressLatestDocumentData = {
    body?: never;
    path?: never;
    query?: never;
    url: '/reader/reading-progress/latest-document';
};

export type GetReaderReadingProgressLatestDocumentErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderReadingProgressLatestDocumentError = GetReaderReadingProgressLatestDocumentErrors[keyof GetReaderReadingProgressLatestDocumentErrors];

export type GetReaderReadingProgressLatestDocumentResponses = {
    /**
     * 最近阅读的文档
     */
    200: DocumentSummary;
    /**
     * 尚未保存阅读位置
     */
    204: void;
};

export type GetReaderReadingProgressLatestDocumentResponse = GetReaderReadingProgressLatestDocumentResponses[keyof GetReaderReadingProgressLatestDocumentResponses];

export type GetReaderReadingProgressByDocumentIdData = {
    body?: never;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/reader/reading-progress/{documentId}';
};

export type GetReaderReadingProgressByDocumentIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderReadingProgressByDocumentIdError = GetReaderReadingProgressByDocumentIdErrors[keyof GetReaderReadingProgressByDocumentIdErrors];

export type GetReaderReadingProgressByDocumentIdResponses = {
    /**
     * 最新阅读位置
     */
    200: ReadingProgress;
    /**
     * 尚未保存阅读位置
     */
    204: void;
};

export type GetReaderReadingProgressByDocumentIdResponse = GetReaderReadingProgressByDocumentIdResponses[keyof GetReaderReadingProgressByDocumentIdResponses];

export type PutReaderReadingProgressByDocumentIdData = {
    body: ReadingProgress;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/reader/reading-progress/{documentId}';
};

export type PutReaderReadingProgressByDocumentIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PutReaderReadingProgressByDocumentIdError = PutReaderReadingProgressByDocumentIdErrors[keyof PutReaderReadingProgressByDocumentIdErrors];

export type PutReaderReadingProgressByDocumentIdResponses = {
    /**
     * 请求成功
     */
    200: ReadingProgress;
};

export type PutReaderReadingProgressByDocumentIdResponse = PutReaderReadingProgressByDocumentIdResponses[keyof PutReaderReadingProgressByDocumentIdResponses];

export type PostReaderBookmarksData = {
    body: BookmarkRequest;
    path?: never;
    query?: never;
    url: '/reader/bookmarks';
};

export type PostReaderBookmarksErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostReaderBookmarksError = PostReaderBookmarksErrors[keyof PostReaderBookmarksErrors];

export type PostReaderBookmarksResponses = {
    /**
     * 请求成功
     */
    201: Bookmark;
};

export type PostReaderBookmarksResponse = PostReaderBookmarksResponses[keyof PostReaderBookmarksResponses];

export type DeleteReaderBookmarksByBookmarkIdData = {
    body?: never;
    path: {
        bookmarkId: string;
    };
    query?: never;
    url: '/reader/bookmarks/{bookmarkId}';
};

export type DeleteReaderBookmarksByBookmarkIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type DeleteReaderBookmarksByBookmarkIdError = DeleteReaderBookmarksByBookmarkIdErrors[keyof DeleteReaderBookmarksByBookmarkIdErrors];

export type DeleteReaderBookmarksByBookmarkIdResponses = {
    /**
     * 已删除
     */
    204: void;
};

export type DeleteReaderBookmarksByBookmarkIdResponse = DeleteReaderBookmarksByBookmarkIdResponses[keyof DeleteReaderBookmarksByBookmarkIdResponses];

export type PostReaderNotesData = {
    body: NoteRequest;
    path?: never;
    query?: never;
    url: '/reader/notes';
};

export type PostReaderNotesErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostReaderNotesError = PostReaderNotesErrors[keyof PostReaderNotesErrors];

export type PostReaderNotesResponses = {
    /**
     * 请求成功
     */
    201: Note;
};

export type PostReaderNotesResponse = PostReaderNotesResponses[keyof PostReaderNotesResponses];

export type PutReaderReviewStatesByNodeIdData = {
    body: ReviewStateRequest;
    path: {
        nodeId: string;
    };
    query?: never;
    url: '/reader/review-states/{nodeId}';
};

export type PutReaderReviewStatesByNodeIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PutReaderReviewStatesByNodeIdError = PutReaderReviewStatesByNodeIdErrors[keyof PutReaderReviewStatesByNodeIdErrors];

export type PutReaderReviewStatesByNodeIdResponses = {
    /**
     * 请求成功
     */
    200: ReviewState;
};

export type PutReaderReviewStatesByNodeIdResponse = PutReaderReviewStatesByNodeIdResponses[keyof PutReaderReviewStatesByNodeIdResponses];

export type GetReaderReviewQueueData = {
    body?: never;
    path?: never;
    query?: {
        documentId?: string;
        limit?: number;
        dueOnly?: boolean;
    };
    url: '/reader/review-queue';
};

export type GetReaderReviewQueueErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetReaderReviewQueueError = GetReaderReviewQueueErrors[keyof GetReaderReviewQueueErrors];

export type GetReaderReviewQueueResponses = {
    /**
     * 请求成功
     */
    200: Array<ReviewQueueItem>;
};

export type GetReaderReviewQueueResponse = GetReaderReviewQueueResponses[keyof GetReaderReviewQueueResponses];

export type GetAdminDocumentsData = {
    body?: never;
    path?: never;
    query?: {
        query?: string;
        page?: number;
        size?: number;
    };
    url: '/admin/documents';
};

export type GetAdminDocumentsErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminDocumentsError = GetAdminDocumentsErrors[keyof GetAdminDocumentsErrors];

export type GetAdminDocumentsResponses = {
    /**
     * 请求成功
     */
    200: AdminDocumentPage;
};

export type GetAdminDocumentsResponse = GetAdminDocumentsResponses[keyof GetAdminDocumentsResponses];

export type GetAdminDocumentsByDocumentIdData = {
    body?: never;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}';
};

export type GetAdminDocumentsByDocumentIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    404: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminDocumentsByDocumentIdError = GetAdminDocumentsByDocumentIdErrors[keyof GetAdminDocumentsByDocumentIdErrors];

export type GetAdminDocumentsByDocumentIdResponses = {
    /**
     * 请求成功
     */
    200: AdminDocumentSummary;
};

export type GetAdminDocumentsByDocumentIdResponse = GetAdminDocumentsByDocumentIdResponses[keyof GetAdminDocumentsByDocumentIdResponses];

export type GetAdminDocumentsByDocumentIdMetadataData = {
    body?: never;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/metadata';
};

export type GetAdminDocumentsByDocumentIdMetadataErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminDocumentsByDocumentIdMetadataError = GetAdminDocumentsByDocumentIdMetadataErrors[keyof GetAdminDocumentsByDocumentIdMetadataErrors];

export type GetAdminDocumentsByDocumentIdMetadataResponses = {
    /**
     * 请求成功
     */
    200: DocumentMetadata;
};

export type GetAdminDocumentsByDocumentIdMetadataResponse = GetAdminDocumentsByDocumentIdMetadataResponses[keyof GetAdminDocumentsByDocumentIdMetadataResponses];

export type PatchAdminDocumentsByDocumentIdMetadataData = {
    body: UpdateDocumentMetadataRequest;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/metadata';
};

export type PatchAdminDocumentsByDocumentIdMetadataErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PatchAdminDocumentsByDocumentIdMetadataError = PatchAdminDocumentsByDocumentIdMetadataErrors[keyof PatchAdminDocumentsByDocumentIdMetadataErrors];

export type PatchAdminDocumentsByDocumentIdMetadataResponses = {
    /**
     * 元数据已更新
     */
    200: DocumentMetadata;
};

export type PatchAdminDocumentsByDocumentIdMetadataResponse = PatchAdminDocumentsByDocumentIdMetadataResponses[keyof PatchAdminDocumentsByDocumentIdMetadataResponses];

export type GetAdminDocumentsByDocumentIdVersionsData = {
    body?: never;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/versions';
};

export type GetAdminDocumentsByDocumentIdVersionsErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminDocumentsByDocumentIdVersionsError = GetAdminDocumentsByDocumentIdVersionsErrors[keyof GetAdminDocumentsByDocumentIdVersionsErrors];

export type GetAdminDocumentsByDocumentIdVersionsResponses = {
    /**
     * 请求成功
     */
    200: Array<VersionSummary>;
};

export type GetAdminDocumentsByDocumentIdVersionsResponse = GetAdminDocumentsByDocumentIdVersionsResponses[keyof GetAdminDocumentsByDocumentIdVersionsResponses];

export type PostAdminDocumentsByDocumentIdTakeDownData = {
    body?: never;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/take-down';
};

export type PostAdminDocumentsByDocumentIdTakeDownErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminDocumentsByDocumentIdTakeDownError = PostAdminDocumentsByDocumentIdTakeDownErrors[keyof PostAdminDocumentsByDocumentIdTakeDownErrors];

export type PostAdminDocumentsByDocumentIdTakeDownResponses = {
    /**
     * 已下架
     */
    204: void;
};

export type PostAdminDocumentsByDocumentIdTakeDownResponse = PostAdminDocumentsByDocumentIdTakeDownResponses[keyof PostAdminDocumentsByDocumentIdTakeDownResponses];

export type PostAdminDocumentsByDocumentIdRestoreData = {
    body?: never;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/restore';
};

export type PostAdminDocumentsByDocumentIdRestoreErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminDocumentsByDocumentIdRestoreError = PostAdminDocumentsByDocumentIdRestoreErrors[keyof PostAdminDocumentsByDocumentIdRestoreErrors];

export type PostAdminDocumentsByDocumentIdRestoreResponses = {
    /**
     * 已恢复
     */
    204: void;
};

export type PostAdminDocumentsByDocumentIdRestoreResponse = PostAdminDocumentsByDocumentIdRestoreResponses[keyof PostAdminDocumentsByDocumentIdRestoreResponses];

export type PostAdminDocumentsByDocumentIdDeletionData = {
    body: DeleteDocumentRequest;
    path: {
        documentId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/deletion';
};

export type PostAdminDocumentsByDocumentIdDeletionErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminDocumentsByDocumentIdDeletionError = PostAdminDocumentsByDocumentIdDeletionErrors[keyof PostAdminDocumentsByDocumentIdDeletionErrors];

export type PostAdminDocumentsByDocumentIdDeletionResponses = {
    /**
     * 已接受删除请求，或返回已存在的幂等任务
     */
    202: DeletionJob;
};

export type PostAdminDocumentsByDocumentIdDeletionResponse = PostAdminDocumentsByDocumentIdDeletionResponses[keyof PostAdminDocumentsByDocumentIdDeletionResponses];

export type GetAdminDocumentDeletionsByJobIdData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/document-deletions/{jobId}';
};

export type GetAdminDocumentDeletionsByJobIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminDocumentDeletionsByJobIdError = GetAdminDocumentDeletionsByJobIdErrors[keyof GetAdminDocumentDeletionsByJobIdErrors];

export type GetAdminDocumentDeletionsByJobIdResponses = {
    /**
     * 删除进度
     */
    200: DeletionJob;
};

export type GetAdminDocumentDeletionsByJobIdResponse = GetAdminDocumentDeletionsByJobIdResponses[keyof GetAdminDocumentDeletionsByJobIdResponses];

export type PostAdminDocumentDeletionsByJobIdRetryData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/document-deletions/{jobId}/retry';
};

export type PostAdminDocumentDeletionsByJobIdRetryErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminDocumentDeletionsByJobIdRetryError = PostAdminDocumentDeletionsByJobIdRetryErrors[keyof PostAdminDocumentDeletionsByJobIdRetryErrors];

export type PostAdminDocumentDeletionsByJobIdRetryResponses = {
    /**
     * 已接受重试请求
     */
    202: DeletionJob;
};

export type PostAdminDocumentDeletionsByJobIdRetryResponse = PostAdminDocumentDeletionsByJobIdRetryResponses[keyof PostAdminDocumentDeletionsByJobIdRetryResponses];

export type PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsData = {
    body?: never;
    path: {
        documentId: string;
        sourceVersionId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/versions/{sourceVersionId}/revisions';
};

export type PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsError = PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsErrors[keyof PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsErrors];

export type PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsResponses = {
    /**
     * 请求成功
     */
    201: VersionSummary;
};

export type PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsResponse = PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsResponses[keyof PostAdminDocumentsByDocumentIdVersionsBySourceVersionIdRevisionsResponses];

export type DeleteAdminVersionsByVersionIdEditorData = {
    body?: never;
    path: {
        versionId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor';
};

export type DeleteAdminVersionsByVersionIdEditorErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type DeleteAdminVersionsByVersionIdEditorError = DeleteAdminVersionsByVersionIdEditorErrors[keyof DeleteAdminVersionsByVersionIdEditorErrors];

export type DeleteAdminVersionsByVersionIdEditorResponses = {
    /**
     * 已删除
     */
    204: void;
};

export type DeleteAdminVersionsByVersionIdEditorResponse = DeleteAdminVersionsByVersionIdEditorResponses[keyof DeleteAdminVersionsByVersionIdEditorResponses];

export type GetAdminVersionsByVersionIdEditorData = {
    body?: never;
    path: {
        versionId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor';
};

export type GetAdminVersionsByVersionIdEditorErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminVersionsByVersionIdEditorError = GetAdminVersionsByVersionIdEditorErrors[keyof GetAdminVersionsByVersionIdEditorErrors];

export type GetAdminVersionsByVersionIdEditorResponses = {
    /**
     * 请求成功
     */
    200: EditorSnapshot;
};

export type GetAdminVersionsByVersionIdEditorResponse = GetAdminVersionsByVersionIdEditorResponses[keyof GetAdminVersionsByVersionIdEditorResponses];

export type GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksData = {
    body?: never;
    path: {
        versionId: string;
        nodeId: string;
    };
    query?: {
        cursor?: string;
        limit?: number;
    };
    url: '/admin/versions/{versionId}/editor/nodes/{nodeId}/blocks';
};

export type GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksError = GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksErrors[keyof GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksErrors];

export type GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponses = {
    /**
     * 请求成功
     */
    200: NodeBlocksPage;
};

export type GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponse = GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponses[keyof GetAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponses];

export type PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksData = {
    body: BlockWriteRequest;
    path: {
        versionId: string;
        nodeId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor/nodes/{nodeId}/blocks';
};

export type PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksError = PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksErrors[keyof PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksErrors];

export type PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponses = {
    /**
     * 请求成功
     */
    201: EditorBlock;
};

export type PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponse = PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponses[keyof PostAdminVersionsByVersionIdEditorNodesByNodeIdBlocksResponses];

export type DeleteAdminVersionsByVersionIdEditorNodesByNodeIdData = {
    body?: never;
    path: {
        versionId: string;
        nodeId: string;
    };
    query: {
        draftRevision: number;
    };
    url: '/admin/versions/{versionId}/editor/nodes/{nodeId}';
};

export type DeleteAdminVersionsByVersionIdEditorNodesByNodeIdErrors = {
    /**
     * 草稿修订号冲突、节点非空或仅剩最后一个节点
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type DeleteAdminVersionsByVersionIdEditorNodesByNodeIdError = DeleteAdminVersionsByVersionIdEditorNodesByNodeIdErrors[keyof DeleteAdminVersionsByVersionIdEditorNodesByNodeIdErrors];

export type DeleteAdminVersionsByVersionIdEditorNodesByNodeIdResponses = {
    /**
     * 更新后的编辑器快照
     */
    200: EditorSnapshot;
};

export type DeleteAdminVersionsByVersionIdEditorNodesByNodeIdResponse = DeleteAdminVersionsByVersionIdEditorNodesByNodeIdResponses[keyof DeleteAdminVersionsByVersionIdEditorNodesByNodeIdResponses];

export type PatchAdminVersionsByVersionIdEditorNodesByNodeIdData = {
    body: UpdateNodeRequest;
    path: {
        versionId: string;
        nodeId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor/nodes/{nodeId}';
};

export type PatchAdminVersionsByVersionIdEditorNodesByNodeIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PatchAdminVersionsByVersionIdEditorNodesByNodeIdError = PatchAdminVersionsByVersionIdEditorNodesByNodeIdErrors[keyof PatchAdminVersionsByVersionIdEditorNodesByNodeIdErrors];

export type PatchAdminVersionsByVersionIdEditorNodesByNodeIdResponses = {
    /**
     * 请求成功
     */
    200: EditorSnapshot;
};

export type PatchAdminVersionsByVersionIdEditorNodesByNodeIdResponse = PatchAdminVersionsByVersionIdEditorNodesByNodeIdResponses[keyof PatchAdminVersionsByVersionIdEditorNodesByNodeIdResponses];

export type PatchAdminVersionsByVersionIdEditorStructureData = {
    body: StructureUpdateRequest;
    path: {
        versionId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor/structure';
};

export type PatchAdminVersionsByVersionIdEditorStructureErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PatchAdminVersionsByVersionIdEditorStructureError = PatchAdminVersionsByVersionIdEditorStructureErrors[keyof PatchAdminVersionsByVersionIdEditorStructureErrors];

export type PatchAdminVersionsByVersionIdEditorStructureResponses = {
    /**
     * 请求成功
     */
    200: EditorSnapshot;
};

export type PatchAdminVersionsByVersionIdEditorStructureResponse = PatchAdminVersionsByVersionIdEditorStructureResponses[keyof PatchAdminVersionsByVersionIdEditorStructureResponses];

export type DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdData = {
    body?: never;
    path: {
        versionId: string;
        blockId: string;
    };
    query: {
        draftRevision: number;
    };
    url: '/admin/versions/{versionId}/editor/blocks/{blockId}';
};

export type DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdError = DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdErrors[keyof DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdErrors];

export type DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdResponses = {
    /**
     * 请求成功
     */
    200: BlockMutationResult;
};

export type DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdResponse = DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdResponses[keyof DeleteAdminVersionsByVersionIdEditorBlocksByBlockIdResponses];

export type PatchAdminVersionsByVersionIdEditorBlocksByBlockIdData = {
    body: BlockWriteRequest;
    path: {
        versionId: string;
        blockId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor/blocks/{blockId}';
};

export type PatchAdminVersionsByVersionIdEditorBlocksByBlockIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PatchAdminVersionsByVersionIdEditorBlocksByBlockIdError = PatchAdminVersionsByVersionIdEditorBlocksByBlockIdErrors[keyof PatchAdminVersionsByVersionIdEditorBlocksByBlockIdErrors];

export type PatchAdminVersionsByVersionIdEditorBlocksByBlockIdResponses = {
    /**
     * 请求成功
     */
    200: EditorBlock;
};

export type PatchAdminVersionsByVersionIdEditorBlocksByBlockIdResponse = PatchAdminVersionsByVersionIdEditorBlocksByBlockIdResponses[keyof PatchAdminVersionsByVersionIdEditorBlocksByBlockIdResponses];

export type PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageData = {
    body: {
        file: Blob | File;
        draftRevision: number;
        alt?: string;
        decorative?: boolean;
        caption?: string;
    };
    path: {
        versionId: string;
        blockId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor/blocks/{blockId}/image';
};

export type PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageError = PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageErrors[keyof PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageErrors];

export type PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageResponses = {
    /**
     * 图片已绑定到内容块
     */
    200: ImageBlockUploadResult;
};

export type PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageResponse = PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageResponses[keyof PostAdminVersionsByVersionIdEditorBlocksByBlockIdImageResponses];

export type GetAdminVersionsByVersionIdEditorAssetsByAssetKeyData = {
    body?: never;
    path: {
        versionId: string;
        assetKey: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor/assets/{assetKey}';
};

export type GetAdminVersionsByVersionIdEditorAssetsByAssetKeyErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    404: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminVersionsByVersionIdEditorAssetsByAssetKeyError = GetAdminVersionsByVersionIdEditorAssetsByAssetKeyErrors[keyof GetAdminVersionsByVersionIdEditorAssetsByAssetKeyErrors];

export type GetAdminVersionsByVersionIdEditorAssetsByAssetKeyResponses = {
    /**
     * 图片二进制内容
     */
    200: unknown;
};

export type PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyData = {
    body: BlockCleanupRequest;
    path: {
        versionId: string;
    };
    query?: never;
    url: '/admin/versions/{versionId}/editor/blocks/cleanup-empty';
};

export type PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyError = PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyErrors[keyof PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyErrors];

export type PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyResponses = {
    /**
     * 请求成功
     */
    200: BlockMutationResult;
};

export type PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyResponse = PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyResponses[keyof PostAdminVersionsByVersionIdEditorBlocksCleanupEmptyResponses];

export type PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishData = {
    body?: never;
    path: {
        documentId: string;
        versionId: string;
    };
    query?: never;
    url: '/admin/documents/{documentId}/versions/{versionId}/publish';
};

export type PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishError = PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishErrors[keyof PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishErrors];

export type PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishResponses = {
    /**
     * 已发布
     */
    204: void;
};

export type PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishResponse = PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishResponses[keyof PostAdminDocumentsByDocumentIdVersionsByVersionIdPublishResponses];

export type PostAdminImportJobsData = {
    body: {
        /**
         * PDF、XLSX、Markdown 或 JSON 导入包，最大 10 MiB；由服务端识别来源类型。
         */
        file: Blob | File;
        targetDocumentId?: string;
    };
    path?: never;
    query?: never;
    url: '/admin/import-jobs';
};

export type PostAdminImportJobsErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminImportJobsError = PostAdminImportJobsErrors[keyof PostAdminImportJobsErrors];

export type PostAdminImportJobsResponses = {
    /**
     * 请求成功
     */
    202: ImportJob;
};

export type PostAdminImportJobsResponse = PostAdminImportJobsResponses[keyof PostAdminImportJobsResponses];

export type GetAdminImportJobsByJobIdData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}';
};

export type GetAdminImportJobsByJobIdErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminImportJobsByJobIdError = GetAdminImportJobsByJobIdErrors[keyof GetAdminImportJobsByJobIdErrors];

export type GetAdminImportJobsByJobIdResponses = {
    /**
     * 请求成功
     */
    200: ImportJob;
};

export type GetAdminImportJobsByJobIdResponse = GetAdminImportJobsByJobIdResponses[keyof GetAdminImportJobsByJobIdResponses];

export type GetAdminImportJobsByJobIdIssuesData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/issues';
};

export type GetAdminImportJobsByJobIdIssuesErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminImportJobsByJobIdIssuesError = GetAdminImportJobsByJobIdIssuesErrors[keyof GetAdminImportJobsByJobIdIssuesErrors];

export type GetAdminImportJobsByJobIdIssuesResponses = {
    /**
     * 请求成功
     */
    200: Array<ImportIssue>;
};

export type GetAdminImportJobsByJobIdIssuesResponse = GetAdminImportJobsByJobIdIssuesResponses[keyof GetAdminImportJobsByJobIdIssuesResponses];

export type GetAdminImportJobsByJobIdDocumentMetadataData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/document-metadata';
};

export type GetAdminImportJobsByJobIdDocumentMetadataErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminImportJobsByJobIdDocumentMetadataError = GetAdminImportJobsByJobIdDocumentMetadataErrors[keyof GetAdminImportJobsByJobIdDocumentMetadataErrors];

export type GetAdminImportJobsByJobIdDocumentMetadataResponses = {
    /**
     * 请求成功
     */
    200: ImportDocumentPreview;
};

export type GetAdminImportJobsByJobIdDocumentMetadataResponse = GetAdminImportJobsByJobIdDocumentMetadataResponses[keyof GetAdminImportJobsByJobIdDocumentMetadataResponses];

export type PatchAdminImportJobsByJobIdDocumentMetadataData = {
    body: UpdateImportDocumentMetadataRequest;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/document-metadata';
};

export type PatchAdminImportJobsByJobIdDocumentMetadataErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    409: Problem;
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PatchAdminImportJobsByJobIdDocumentMetadataError = PatchAdminImportJobsByJobIdDocumentMetadataErrors[keyof PatchAdminImportJobsByJobIdDocumentMetadataErrors];

export type PatchAdminImportJobsByJobIdDocumentMetadataResponses = {
    /**
     * 暂存元数据已更新
     */
    200: ImportDocumentPreview;
};

export type PatchAdminImportJobsByJobIdDocumentMetadataResponse = PatchAdminImportJobsByJobIdDocumentMetadataResponses[keyof PatchAdminImportJobsByJobIdDocumentMetadataResponses];

export type GetAdminImportJobsByJobIdRawExtractionData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/raw-extraction';
};

export type GetAdminImportJobsByJobIdRawExtractionErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminImportJobsByJobIdRawExtractionError = GetAdminImportJobsByJobIdRawExtractionErrors[keyof GetAdminImportJobsByJobIdRawExtractionErrors];

export type GetAdminImportJobsByJobIdRawExtractionResponses = {
    /**
     * 请求成功
     */
    200: JsonValue;
};

export type GetAdminImportJobsByJobIdRawExtractionResponse = GetAdminImportJobsByJobIdRawExtractionResponses[keyof GetAdminImportJobsByJobIdRawExtractionResponses];

export type GetAdminImportJobsByJobIdSourceFileData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/source-file';
};

export type GetAdminImportJobsByJobIdSourceFileErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminImportJobsByJobIdSourceFileError = GetAdminImportJobsByJobIdSourceFileErrors[keyof GetAdminImportJobsByJobIdSourceFileErrors];

export type GetAdminImportJobsByJobIdSourceFileResponses = {
    /**
     * 原始来源文件
     */
    200: Blob | File;
};

export type GetAdminImportJobsByJobIdSourceFileResponse = GetAdminImportJobsByJobIdSourceFileResponses[keyof GetAdminImportJobsByJobIdSourceFileResponses];

export type GetAdminImportJobsByJobIdNormalizedPackageData = {
    body?: never;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/normalized-package';
};

export type GetAdminImportJobsByJobIdNormalizedPackageErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type GetAdminImportJobsByJobIdNormalizedPackageError = GetAdminImportJobsByJobIdNormalizedPackageErrors[keyof GetAdminImportJobsByJobIdNormalizedPackageErrors];

export type GetAdminImportJobsByJobIdNormalizedPackageResponses = {
    /**
     * 请求成功
     */
    200: DocumentPackage;
};

export type GetAdminImportJobsByJobIdNormalizedPackageResponse = GetAdminImportJobsByJobIdNormalizedPackageResponses[keyof GetAdminImportJobsByJobIdNormalizedPackageResponses];

export type PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyData = {
    body: JsonObject;
    path: {
        jobId: string;
        sectionKey: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/normalized-package/sections/{sectionKey}';
};

export type PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyError = PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyErrors[keyof PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyErrors];

export type PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyResponses = {
    /**
     * 请求成功
     */
    200: DocumentPackage;
};

export type PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyResponse = PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyResponses[keyof PatchAdminImportJobsByJobIdNormalizedPackageSectionsBySectionKeyResponses];

export type PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyData = {
    body: JsonObject;
    path: {
        jobId: string;
        blockKey: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/normalized-package/blocks/{blockKey}';
};

export type PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyError = PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyErrors[keyof PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyErrors];

export type PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyResponses = {
    /**
     * 请求成功
     */
    200: DocumentPackage;
};

export type PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyResponse = PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyResponses[keyof PatchAdminImportJobsByJobIdNormalizedPackageBlocksByBlockKeyResponses];

export type PostAdminImportJobsByJobIdCommitData = {
    body?: CommitImportRequest;
    path: {
        jobId: string;
    };
    query?: never;
    url: '/admin/import-jobs/{jobId}/commit';
};

export type PostAdminImportJobsByJobIdCommitErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminImportJobsByJobIdCommitError = PostAdminImportJobsByJobIdCommitErrors[keyof PostAdminImportJobsByJobIdCommitErrors];

export type PostAdminImportJobsByJobIdCommitResponses = {
    /**
     * 请求成功
     */
    201: DocumentVersion;
};

export type PostAdminImportJobsByJobIdCommitResponse = PostAdminImportJobsByJobIdCommitResponses[keyof PostAdminImportJobsByJobIdCommitResponses];

export type PostAdminExportsData = {
    body: ExportRequest;
    path?: never;
    query?: never;
    url: '/admin/exports';
};

export type PostAdminExportsErrors = {
    /**
     * 符合 RFC 9457 的问题详情
     */
    default: Problem;
};

export type PostAdminExportsError = PostAdminExportsErrors[keyof PostAdminExportsErrors];

export type PostAdminExportsResponses = {
    /**
     * 按请求格式导出内容
     */
    200: DocumentPackage;
};

export type PostAdminExportsResponse = PostAdminExportsResponses[keyof PostAdminExportsResponses];
