'use client';

import { useState } from 'react';
import { Alert, App, Button, Card, Descriptions, Form, Input, Select, Space, Tag } from 'antd';
import { getIdentityProfile, updateIdentityProfile } from '@/lib/api';
import { IDENTITY_OPTIONS } from '@/lib/settings';
import type { Identity, IdentityProfile } from '@/lib/types';

function parseDomains(raw: string | null): string[] {
  if (!raw) return [];
  try {
    const v: unknown = JSON.parse(raw);
    return Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [];
  } catch {
    return [];
  }
}

export default function AdminIdentityPanel() {
  const { message } = App.useApp();
  const [targetUserId, setTargetUserId] = useState('');
  const [profile, setProfile] = useState<IdentityProfile | null>(null);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editIdentity, setEditIdentity] = useState<Identity | undefined>(undefined);

  const query = async () => {
    const uid = targetUserId.trim();
    if (!uid) {
      message.warning('请输入目标用户 ID');
      return;
    }
    setLoading(true);
    setError(null);
    setProfile(null);
    try {
      const data = await getIdentityProfile(uid);
      setProfile(data);
      setEditIdentity((data.businessIdentity as Identity) || undefined);
    } catch (e) {
      setError(e instanceof Error ? e.message : '查询失败');
    } finally {
      setLoading(false);
    }
  };

  const save = async () => {
    if (!profile || !editIdentity) {
      message.warning('请选择新的业务身份');
      return;
    }
    setSaving(true);
    try {
      const ack = await updateIdentityProfile(profile.userId, {
        businessIdentity: editIdentity,
        assignedDomains: profile.assignedDomains ?? undefined,
      });
      message.success(`已更新用户 ${ack.userId} 的身份`);
      // 后端 PUT 仅回执，重新拉取完整画像避免视图失真
      setProfile(await getIdentityProfile(profile.userId));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '更新失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Card title="管理员区：用户身份管理">
      <Space.Compact style={{ width: 480, marginBottom: 16 }}>
        <Input
          placeholder="输入目标用户 ID"
          value={targetUserId}
          onChange={(e) => setTargetUserId(e.target.value)}
          onPressEnter={query}
        />
        <Button type="primary" loading={loading} onClick={query}>
          查询
        </Button>
      </Space.Compact>

      {error && (
        <Alert
          type="error"
          showIcon
          title="查询失败"
          description={error}
          action={
            <Button size="small" onClick={query}>
              重试
            </Button>
          }
        />
      )}

      {profile && (
        <>
          <Descriptions bordered size="small" column={1} style={{ maxWidth: 720 }}>
            <Descriptions.Item label="用户 ID">{profile.userId}</Descriptions.Item>
            <Descriptions.Item label="当前业务身份">
              <Tag color="blue">{profile.businessIdentity || '未设置'}</Tag>
              {profile.identityLabel && <span>{profile.identityLabel}</span>}
            </Descriptions.Item>
            <Descriptions.Item label="可访问子域">
              {profile.allowedSubDomains?.length
                ? profile.allowedSubDomains.map((s) => <Tag key={s}>{s}</Tag>)
                : '不限'}
            </Descriptions.Item>
            <Descriptions.Item label="分配业务域">
              {parseDomains(profile.assignedDomains).length
                ? parseDomains(profile.assignedDomains).map((s) => <Tag key={s}>{s}</Tag>)
                : '未限制'}
            </Descriptions.Item>
          </Descriptions>
          <Form layout="inline" style={{ marginTop: 16 }}>
            <Form.Item label="新业务身份">
              <Select
                style={{ width: 200 }}
                value={editIdentity}
                onChange={(v) => setEditIdentity(v)}
                options={IDENTITY_OPTIONS}
                placeholder="选择身份"
              />
            </Form.Item>
            <Form.Item>
              <Button type="primary" loading={saving} onClick={save}>
                更新身份
              </Button>
            </Form.Item>
          </Form>
        </>
      )}
    </Card>
  );
}
