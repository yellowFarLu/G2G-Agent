'use client';

import { Button, Empty, Progress, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ExtractedField, FieldSource } from '@/lib/types';

const SOURCE_TAG: Record<FieldSource, { color: string; label: string }> = {
  MODEL: { color: 'blue', label: '模型' },
  RULE: { color: 'purple', label: '规则' },
  HUMAN: { color: 'gold', label: '人工' },
};

function confidenceColor(c: number): string {
  if (c < 0.75) return '#ff4d4f';
  if (c < 0.9) return '#fa8c16';
  return '#52c41a';
}

interface Props {
  fields: ExtractedField[];
  loading: boolean;
  onShowLineage: (fieldKey: string) => void;
}

export default function FieldTable({ fields, loading, onShowLineage }: Props) {
  const columns: ColumnsType<ExtractedField> = [
    { title: '字段', dataIndex: 'fieldLabel', key: 'fieldLabel', width: 160 },
    { title: '字段键', dataIndex: 'fieldKey', key: 'fieldKey', width: 160 },
    {
      title: '取值',
      dataIndex: 'valueText',
      key: 'valueText',
      render: (v: string) => (
        <Typography.Paragraph
          style={{ marginBottom: 0 }}
          ellipsis={{ rows: 2, expandable: true, symbol: '展开' }}
        >
          {v}
        </Typography.Paragraph>
      ),
    },
    {
      title: '置信度',
      dataIndex: 'confidence',
      key: 'confidence',
      width: 150,
      render: (v: number) => (
        <Progress
          percent={Math.round(v * 100)}
          size="small"
          strokeColor={confidenceColor(v)}
          format={() => `${(v).toFixed(2)}`}
        />
      ),
      sorter: (a, b) => a.confidence - b.confidence,
    },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 90,
      render: (v: FieldSource) => {
        const meta = SOURCE_TAG[v] ?? { color: 'default', label: v };
        return <Tag color={meta.color}>{meta.label}</Tag>;
      },
    },
    {
      title: '校验',
      dataIndex: 'valid',
      key: 'valid',
      width: 80,
      render: (v: boolean) => (v ? <Tag color="success">通过</Tag> : <Tag color="error">未过</Tag>),
    },
    {
      title: '需复核',
      dataIndex: 'reviewRequired',
      key: 'reviewRequired',
      width: 90,
      render: (v: boolean) => (v ? <Tag color="warning">是</Tag> : <Tag>否</Tag>),
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, record) => (
        <Button size="small" type="link" onClick={() => onShowLineage(record.fieldKey)}>
          来源
        </Button>
      ),
    },
  ];

  return (
    <Table<ExtractedField>
      rowKey="id"
      columns={columns}
      dataSource={fields}
      loading={loading}
      pagination={{ pageSize: 15, showTotal: (t) => `共 ${t} 条` }}
      locale={{ emptyText: <Empty description="暂无抽取字段" /> }}
    />
  );
}
