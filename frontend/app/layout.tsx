import type { Metadata } from 'next';
import { Suspense } from 'react';
import { Spin } from 'antd';
import { AntdRegistry } from '@ant-design/nextjs-registry';
import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import AppShell from '@/components/AppShell';
import './globals.css';

export const metadata: Metadata = {
  title: 'Wiki Agent',
  description: 'Wiki Agent 知识库管理与人工协作工作台',
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body>
        <AntdRegistry>
          <ConfigProvider
            locale={zhCN}
            theme={{
              token: {
                colorPrimary: '#6366f1',
                colorInfo: '#6366f1',
                borderRadius: 10,
                fontFamily:
                  "'Inter', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, -apple-system, sans-serif",
              },
              components: {
                Layout: { bodyBg: 'transparent', headerBg: 'transparent' },
              },
            }}
          >
            <AntdApp>
              <Suspense
                fallback={
                  <div style={{ textAlign: 'center', padding: 80 }}>
                    <Spin size="large" />
                  </div>
                }
              >
                <AppShell>{children}</AppShell>
              </Suspense>
            </AntdApp>
          </ConfigProvider>
        </AntdRegistry>
      </body>
    </html>
  );
}
