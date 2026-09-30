export interface HealthStatus {
    status?: string;
    components?: Record<string, HealthStatus>;
}

export interface MainRuntime {
    maintenance?: boolean;
    releaseCommit?: string;
    activeWrites?: number;
    importJobs?: number;
    deletionJobs?: number;
    jvmUsedBytes?: number;
    dbConnectionsActive?: number;
    httpMeanMs?: number;
}

export interface HealthSnapshot {
    checkedAt: string;
    overall: HealthStatus;
    liveness: HealthStatus;
    readiness: HealthStatus;
    drain: MainRuntime;
    eligible: boolean;
    blockers: string[];
}

export interface Release {
    id: string;
    runId: number;
    commit: string;
    sha256: string;
    bytes: number;
    stagedAt: string;
}

export interface StageEvent {
    at: string;
    stage: string;
    message: string;
}

export interface Operation {
    id: string;
    releaseId: string;
    status: string;
    stage: string;
    backupId: string | null;
    message: string;
    startedAt: string;
    updatedAt: string;
    events: StageEvent[];
}

export interface Dashboard {
    health: HealthSnapshot;
    latestOperation: Operation | null;
    feedCursor: number;
}

export interface Overview {
    health: HealthSnapshot;
    state: { releases: Release[]; operations: Operation[] };
    feedCursor: number;
}

export interface Page<T> {
    items: T[];
    page: number;
    size: number;
    total: number;
    hasNext: boolean;
}

export type FeedKind = "STAGE" | "COMMAND_STDOUT" | "COMMAND_STDERR";

export interface FeedEntry {
    id: number;
    operationId: string;
    at: string;
    kind: FeedKind;
    stage: string | null;
    message: string;
}

export interface FeedPage {
    entries: FeedEntry[];
    nextBeforeId: number | null;
}