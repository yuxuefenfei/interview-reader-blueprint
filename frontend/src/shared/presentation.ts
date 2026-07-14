export const statusLabel: Record<string, string> = {
  UPLOADED: "已上传", PREFLIGHT: "预检中", EXTRACTING: "提取中", NORMALIZING: "规范化中", VALIDATING: "校验中", READY: "待提交", REVIEW_REQUIRED: "需要复核", IMPORTED: "已生成草稿", FAILED: "失败", CANCELED: "已取消", COMMITTED: "已提交",
  DRAFT: "草稿", PUBLISHED: "已发布", RETIRED: "已退役", PDF: "PDF 文档", EXCEL: "Excel 工作簿", MARKDOWN: "Markdown", JSON_PACKAGE: "JSON 文档包"
};
export function zh(value: string | null | undefined): string { return value ? statusLabel[value] || value : "-"; }
export function formatTime(value: string | null | undefined): string { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "-"; }