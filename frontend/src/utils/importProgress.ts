export const processingStages = [
  { code: "UPLOADED", label: "上传" },
  { code: "PREFLIGHT", label: "预检" },
  { code: "EXTRACTING", label: "提取" },
  { code: "NORMALIZING", label: "规范化" },
  { code: "VALIDATING", label: "校验" }
] as const;

type StageState = "completed" | "active" | "failed" | "pending";

const completedStatuses = new Set(["READY", "REVIEW_REQUIRED", "IMPORTED"]);

export function importStageSummary(status: string, stage: string | null): string {
  if (status === "READY") return "文件已完成解析与校验，可生成可编辑草稿。";
  if (status === "REVIEW_REQUIRED") return "存在需要人工确认的校验问题，修订后可继续提交。";
  if (status === "IMPORTED") return "草稿已生成，可前往文档管理继续修订。";
  if (status === "FAILED") return "导入未完成，请根据错误信息调整文件后重试。";
  if (status === "CANCELED") return "导入任务已取消。";
  return processingStageDescription(stage || status);
}

export function importActiveStageIndex(status: string, stage: string | null): number {
  if (completedStatuses.has(status)) return processingStages.length;
  const currentStage = stage || status;
  return Math.max(0, processingStages.findIndex(({ code }) => code === currentStage));
}

export function importStageState(status: string, stage: string | null, index: number): StageState {
  if (completedStatuses.has(status)) return "completed";
  if (status === "CANCELED") return "pending";
  const activeIndex = importActiveStageIndex(status, stage);
  if (index < activeIndex) return "completed";
  if (index === activeIndex) return status === "FAILED" ? "failed" : "active";
  return "pending";
}

function processingStageDescription(stage: string): string {
  return ({
    UPLOADED: "文件已上传，正在开始导入任务。",
    PREFLIGHT: "正在检查文件完整性和可识别性。",
    EXTRACTING: "正在提取文件中的文本与结构。",
    NORMALIZING: "正在整理内容块与文档层级。",
    VALIDATING: "正在校验内容结构与导入结果。"
  } as Record<string, string>)[stage] || "正在处理文件。";
}
