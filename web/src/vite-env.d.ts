/// <reference types="vite/client" />

/**
 * 自定义环境变量声明（与 vite/client 的 ImportMetaEnv 做声明合并）。
 */
interface ImportMetaEnv {
  /** 为 "true" 时聊天界面走前端 mock Agent，不请求后端；缺省走真实 /api */
  readonly VITE_USE_MOCK?: string;
}
