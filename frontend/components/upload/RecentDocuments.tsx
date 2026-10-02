'use client';

import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Empty, Table } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { useRouter } from 'next/navigation';
import type { ColumnsType } from 'antd/es/table';
import { listDocuments } from '@/lib/api';
import type { DocumentView } from '@/lib/types';
import DocStatusTag from '@/components/common/DocStatusTag';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

export default function RecentDocuments() {
  const router = useRouter();
  const [docs, setDocs] = useState<DocumentView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDocs(await listDocuments());
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const columns: ColumnsType<DocumentView> = [
    { title: '文件名', dataIndex: 'filename', key: 'filename' },
    { title: '类型', dataIndex: 'docType', key: 'docType', width: 120 },
    {
      title: '大小',
      dataIndex: 'sizeBytes',
      key: 'sizeBytes',
      width: 120,
      render: (v: number) => formatSize(v),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (v: string) => <DocStatusTag status={v} />,
    },
    { title: '上传时间', dataIndex: 'createdAt', key: 'createdAt', width: 200 },
  ];

  return (
    <Card
      title="最近文档"
      extra={
        <Button icon={<ReloadOutlined />} onClick={load} loading={loading}>
          刷新
        </Button>
      }
    >
      {error && (
        <Alert
          type="error"
          showIcon
          title="文档列表加载失败"
          description={error}
          style={{ marginBottom: 16 }}
          action={
            <Button size="small" onClick={load}>
              重试
            </Button>
          }
        />
      )}
      <Table<DocumentView>
        rowKey="id"
        columns={columns}
        dataSource={docs}
        loading={loading}
        pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
        locale={{ emptyText: <Empty description="暂无文档，请先上传材料" /> }}
        onRow={(record) => ({
          style: { cursor: 'pointer' },
          onClick: () => router.push(`/results/${record.id}`),
        })}
      />
    </Card>
  );
}
