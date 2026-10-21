import { Modal } from 'antd';
import type { ReactNode } from 'react';

export function AppModal({
  open,
  title,
  onCancel,
  children,
  footer = null,
  width = 520,
  confirmLoading = false,
}: {
  open: boolean;
  title: string;
  onCancel: () => void;
  children: ReactNode;
  footer?: ReactNode;
  width?: number;
  confirmLoading?: boolean;
}) {
  return (
    <Modal
      open={open}
      title={title}
      onCancel={onCancel}
      footer={footer}
      width={`min(${width}px, calc(100vw - 24px))`}
      confirmLoading={confirmLoading}
      destroyOnHidden
      centered
      maskClosable={false}
      styles={{ body: { maxHeight: 'calc(100dvh - 160px)', overflowY: 'auto' } }}
    >
      {children}
    </Modal>
  );
}
