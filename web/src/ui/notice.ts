import { App as AntdApp } from 'antd';

export function useWorkbenchNotice() {
  const { message, modal } = AntdApp.useApp();
  return {
    success(text: string) {
      void message.success(text);
    },
    error(text: string) {
      void message.error(text);
    },
    confirm(content: string): Promise<boolean> {
      return new Promise((resolve) => {
        modal.confirm({
          title: '请确认',
          content,
          okText: '确认',
          cancelText: '取消',
          onOk: () => resolve(true),
          onCancel: () => resolve(false),
        });
      });
    },
  };
}
