import { Drawer } from 'antd';
import type { ReactNode } from 'react';

export function AppDrawer({
  open,
  title,
  onClose,
  children,
  width = 420,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  children: ReactNode;
  width?: number;
}) {
  return (
    <Drawer open={open} title={title} onClose={onClose} width={width} destroyOnHidden>
      {children}
    </Drawer>
  );
}
