import type { ThemeConfig } from 'antd';

/** 对齐 global.css :root，不大改深蓝侧栏与主色。 */
export const sjherpTheme: ThemeConfig = {
  cssVar: true,
  token: {
    colorPrimary: '#2563eb',
    colorInfo: '#2563eb',
    colorError: '#dc2626',
    colorTextBase: '#1f2733',
    colorBorder: '#d9dee5',
    colorBgLayout: '#f5f6f8',
    colorBgContainer: '#ffffff',
    colorBgElevated: '#ffffff',
    borderRadius: 6,
    fontFamily: "-apple-system, 'Segoe UI', 'Microsoft YaHei', 'PingFang SC', sans-serif",
  },
  components: {
    Table: {
      headerBg: '#f8fafc',
      rowHoverBg: '#eff6ff',
    },
    Button: {
      primaryShadow: 'none',
    },
  },
};
