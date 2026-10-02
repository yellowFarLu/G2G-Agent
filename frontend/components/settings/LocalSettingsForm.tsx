'use client';

import { useEffect } from 'react';
import { App, Button, Card, Form, Input, Select } from 'antd';
import {
  DOMAIN_OPTIONS,
  getSettings,
  IDENTITY_OPTIONS,
  saveSettings,
  SUB_DOMAIN_OPTIONS,
  type LocalSettings,
} from '@/lib/settings';

export default function LocalSettingsForm() {
  const { message } = App.useApp();
  const [form] = Form.useForm<LocalSettings>();

  useEffect(() => {
    form.setFieldsValue(getSettings());
  }, [form]);

  const onFinish = (values: LocalSettings) => {
    saveSettings({
      userId: values.userId?.trim() || 'anonymous',
      identity: values.identity || '',
      domain: values.domain || '',
      subDomain: values.subDomain || '',
    });
    message.success('身份设置已保存');
  };

  return (
    <Card title="本机身份设置" style={{ marginBottom: 24 }}>
      <Form
        form={form}
        layout="vertical"
        style={{ maxWidth: 480 }}
        onFinish={onFinish}
        initialValues={{ userId: 'anonymous', identity: '', domain: '', subDomain: '' }}
      >
        <Form.Item
          name="userId"
          label="用户 ID（X-User-Id）"
          rules={[{ required: true, message: '请输入用户 ID' }]}
        >
          <Input placeholder="例如 zhangsan" allowClear />
        </Form.Item>
        <Form.Item name="identity" label="业务身份（X-Business-Identity）">
          <Select
            allowClear
            placeholder="未设置"
            options={IDENTITY_OPTIONS}
          />
        </Form.Item>
        <Form.Item name="domain" label="默认业务域（domain）">
          <Select allowClear placeholder="不限" options={DOMAIN_OPTIONS} />
        </Form.Item>
        <Form.Item name="subDomain" label="默认子域（subDomain）">
          <Select allowClear placeholder="不限" options={SUB_DOMAIN_OPTIONS} />
        </Form.Item>
        <Form.Item>
          <Button type="primary" htmlType="submit">
            保存
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}
