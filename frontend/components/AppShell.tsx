'use client';

import React, { useCallback, useEffect, useState } from 'react';
import { Layout, Menu, Tag, Typography } from 'antd';
import {
  CloudUploadOutlined,
  MessageOutlined,
  NodeIndexOutlined,
  ScheduleOutlined,
  SettingOutlined,
  ToolOutlined,
} from '@ant-design/icons';
import { usePathname, useRouter } from 'next/navigation';
import { getSettings, IDENTITY_LABELS, type LocalSettings } from '@/lib/settings';

const { Sider, Header, Content } = Layout;

const MENU_ITEMS = [
  { key: '/upload', icon: <CloudUploadOutlined />, label: '材料上传' },
  { key: '/tasks', icon: <ScheduleOutlined />, label: '任务中心' },
  { key: '/chat', icon: <MessageOutlined />, label: '对话' },
  { key: '/workbench', icon: <ToolOutlined />, label: '人工工作台' },
  { key: '/graph', icon: <NodeIndexOutlined />, label: '知识图谱' },
  { key: '/settings', icon: <SettingOutlined />, label: '身份设置' },
];

function selectedKey(pathname: string): string {
  if (pathname.startsWith('/results')) return '/upload';
  const hit = MENU_ITEMS.map((m) => m.key)
    .filter((k) => pathname === k || pathname.startsWith(`${k}/`))
    .sort((a, b) => b.length - a.length)[0];
  return hit ?? '/upload';
}

export default function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [settings, setSettings] = useState<LocalSettings | null>(null);

  const refresh = useCallback(() => {
    setSettings(getSettings());
  }, []);

  useEffect(() => {
    refresh();
    window.addEventListener('wikiagent-settings-changed', refresh);
    window.addEventListener('storage', refresh);
    return () => {
      window.removeEventListener('wikiagent-settings-changed', refresh);
      window.removeEventListener('storage', refresh);
    };
  }, [refresh]);

  useEffect(() => {
    refresh();
  }, [pathname, refresh]);

  return (
    <Layout style={{ minHeight: '100vh', minWidth: 1200 }}>
      <Sider theme="dark" width={200}>
        <div
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fff',
            fontWeight: 600,
            fontSize: 16,
          }}
        >
          wiki-Agent 管理台
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey(pathname)]}
          items={MENU_ITEMS}
          onClick={({ key }) => router.push(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: '#fff',
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            gap: 12,
            borderBottom: '1px solid #f0f0f0',
            cursor: 'pointer',
          }}
          onClick={() => router.push('/settings')}
          title="点击进入身份设置"
        >
          <Typography.Text type="secondary">当前用户：</Typography.Text>
          <Typography.Text strong>{settings?.userId ?? 'anonymous'}</Typography.Text>
          <Typography.Text type="secondary">身份：</Typography.Text>
          <Tag color="blue">
            {settings?.identity ? (IDENTITY_LABELS[settings.identity] ?? settings.identity) : '未设置'}
          </Tag>
        </Header>
        <Content style={{ padding: 24, overflow: 'auto' }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
