'use client';

import React, { useCallback, useEffect } from 'react';
import { Layout, Tag } from 'antd';
import { UserOutlined } from '@ant-design/icons';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { getSettings, IDENTITY_LABELS, type LocalSettings } from '@/lib/settings';

const { Header, Content } = Layout;

export default function AppShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [settings, setSettings] = React.useState<LocalSettings | null>(null);

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
  }, [pathname, searchParams, refresh]);

  return (
    <Layout style={{ minHeight: '100vh', minWidth: 1200, background: 'transparent' }}>
      <Header className="wa-header">
        <div className="wa-brand-wrap" onClick={() => router.push('/?tab=upload')}>
          <span className="wa-logo">🧭</span>
          <span className="wa-brand">Wiki Agent</span>
        </div>
        <div className="wa-userchip" onClick={() => router.push('/?tab=settings')} title="点击进入身份设置">
          <UserOutlined />
          <span className="wa-user-label">当前用户：</span>
          <strong>{settings?.userId ?? 'anonymous'}</strong>
          <span className="wa-user-divider" />
          <span className="wa-user-label">身份：</span>
          <Tag className="wa-identity-tag">
            {settings?.identity ? (IDENTITY_LABELS[settings.identity] ?? settings.identity) : '未设置'}
          </Tag>
        </div>
      </Header>
      <Content style={{ padding: 24, overflow: 'auto', background: 'transparent' }}>{children}</Content>
    </Layout>
  );
}
