export const statusLabel: Record<string, string> = {
  UPLOADED: "已上传", PREFLIGHT: "预检中", EXTRACTING: "提取中", NORMALIZING: "规范化中", VALIDATING: "校验中", READY: "待提交", REVIEW_REQUIRED: "需要复核", IMPORTED: "已生成草稿", FAILED: "失败", CANCELED: "已取消", COMMITTED: "已提交", DRAFT_DISCARDED: "草稿已丢弃",
  DRAFT: "草稿", PUBLISHED: "已发布", RETIRED: "已退役", PDF: "PDF 文档", EXCEL: "Excel 工作簿", MARKDOWN: "Markdown", JSON_PACKAGE: "JSON 文档包", PART: "篇章", CHAPTER: "章节", SECTION: "小节", SUBSECTION: "子节", QUESTION: "面试问题", APPENDIX: "附录", OTHER: "其他", ANSWER: "答案", EXPLANATION: "解析", CONCLUSION: "结论", INTRODUCTION: "导读", DIRECTORY: "目录", paragraph: "正文", heading_note: "标题说明", unordered_list: "无序列表", ordered_list: "有序列表", code: "代码", table: "表格", quote: "引用", callout: "提示", formula: "公式", image: "图片", divider: "分隔线", table_snapshot: "表格快照"
};
export function zh(value: string | null | undefined): string { return value ? statusLabel[value] || value : "-"; }

const importIssueLabel: Record<string, (page: number | null) => string> = {
  PDF_PAGE_TEXT_UNMAPPED: (page) => `第 ${page ?? "-"} 页含有可读取文本，但未能自动划分为内容块。生成草稿后可在编辑器中补录。`,
  PDF_TABLE_REVIEW_REQUIRED: (page) => `第 ${page ?? "-"} 页疑似表格，已保留为低置信度快照，请在草稿中核对。`,
  PDF_SECTION_EMPTY: (page) => `第 ${page ?? "-"} 页对应的章节未提取到可编辑文本。`,
  PDF_OUTLINE_MISSING: () => "源 PDF 没有目录书签，已按单一文档章节导入。",
  PDF_TEXT_EMPTY: () => "未能从 PDF 的文本层提取可编辑内容。",
  PDF_ENCRYPTED: () => "PDF 已加密，暂不支持导入。",
  PDF_MAGIC_INVALID: () => "上传文件不是有效的 PDF。"
};

export function importIssueMessage(issueCode: string, sourcePage: number | null, fallback: string): string {
  return importIssueLabel[issueCode]?.(sourcePage) || fallback;
}
export function formatTime(value: string | null | undefined): string { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "-"; }
