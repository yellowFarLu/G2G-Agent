'use client';

import { Typography } from 'antd';
import LocalSettingsForm from '@/components/settings/LocalSettingsForm';
import AdminIdentityPanel from '@/components/settings/AdminIdentityPanel';

export default function SettingsPage() {
  return (
    <div>
      <Typography.Title level={3}>身份设置</Typography.Title>
      <LocalSettingsForm />
      <AdminIdentityPanel />
    </div>
  );
}
