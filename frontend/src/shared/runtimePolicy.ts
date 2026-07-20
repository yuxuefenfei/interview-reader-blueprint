/** 跨模块共享的前端运行策略；修改后由组件测试和生产构建统一验证。 */
export const IMPORT_POLL_INITIAL_DELAY_MS = 1_000;
export const IMPORT_POLL_MAX_DELAY_MS = 5_000;
export const IMPORT_POLL_BACKOFF_FACTOR = 1.5;
export const OFFLINE_CONTENT_CACHE_MAX_ITEMS = 30;