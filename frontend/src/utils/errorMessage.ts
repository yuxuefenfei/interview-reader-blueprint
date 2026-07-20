/** 将已标准化的接口错误转换为用户文案，同时保留各页面自己的兜底语义。 */
export function toUserMessage(error: unknown, fallback: string): string {
  return error instanceof Error && error.message.trim() ? error.message : fallback;
}
