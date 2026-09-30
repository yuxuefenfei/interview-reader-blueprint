export const STAGE_LABELS: Record<string, string> = {
    PRECHECKING: "预检中", STOP_WRITES: "关闭写入", DRAINING: "等待任务排空",
    BACKING_UP: "备份数据", STOPPING: "停止主程序", SWITCHING: "切换升级包",
    STARTING: "启动新版本", READINESS: "验证就绪", OPENING: "恢复写入",
    SUCCEEDED: "升级成功", ROLLING_BACK: "自动回滚", RESTORING_DB: "恢复数据库",
    RESTORING_FILES: "恢复文件和旧版", STARTING_OLD: "验证旧版",
    ROLLED_BACK: "已回滚", FAILED: "操作失败", INTERRUPTED: "控制台中断",
    GATED_FAILURE: "写入保持关闭", ROLLBACK_FAILED: "回滚失败",
    RECOVERING: "人工恢复", RECOVERY_FAILED: "恢复失败",
    POST_OPEN_ERROR: "状态待核实", ABORTING: "恢复旧版写入", RESTORED: "恢复完成"
};
export const STATUS_LABELS: Record<string, string> = {
    RUNNING: "进行中", SUCCEEDED: "升级成功", FAILED: "失败",
    ROLLED_BACK: "已回滚", NEEDS_OPERATOR: "需要人工处理", RESTORED: "已恢复"
};

export function stageLabel(value: string | null | undefined): string {
    return value ? STAGE_LABELS[value] ?? value : "尚无操作";
}

export function statusLabel(value: string | null | undefined): string {
    return value ? STATUS_LABELS[value] ?? value : "尚无操作";
}

export function formatTime(value: string | null | undefined): string {
    if (!value) return "—";
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN", {hour12: false});
}

export function formatBytes(value: number | null | undefined): string {
    if (value === null || value === undefined || !Number.isFinite(value)) return "—";
    return value >= 1_073_741_824 ? `${(value / 1_073_741_824).toFixed(1)} GB`
        : `${(value / 1_048_576).toFixed(1)} MB`;
}

export function shortId(value: string | null | undefined): string {
    return value ? value.slice(0, 12) : "—";
}

export function statusTag(value: string): "success" | "danger" | "warning" | "info" {
    if (value === "SUCCEEDED" || value === "RESTORED") return "success";
    if (value === "NEEDS_OPERATOR") return "danger";
    if (value === "FAILED" || value === "ROLLED_BACK") return "warning";
    return "info";
}