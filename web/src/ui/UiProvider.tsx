import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import type { ReactNode } from 'react';
import { sjherpTheme } from './theme.ts';

/** 根级主题与中文 locale；Message/Modal 静态方法必须挂在 AntdApp 下。 */
export function UiProvider({ children }: { children: ReactNode }) {
  return (
    <ConfigProvider locale={zhCN} theme={sjherpTheme}>
      <AntdApp>{children}</AntdApp>
    </ConfigProvider>
  );
}
