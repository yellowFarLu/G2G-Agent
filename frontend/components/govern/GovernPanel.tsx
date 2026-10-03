'use client';

import { useCallback, useEffect, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Card,
  Statistic,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { getMetricsAggregation } from '@/lib/api';
import type { MetricsAggregation, StaleKnowledgeItem } from '@/lib/types';
import ConflictReviewPanel from './ConflictReviewPanel';

function pct(v?: number) {
  if (v === undefined || Number.isNaN(v)) return '-';
  return `${(v * 100).toFixed(1)}%`;
}

function KnowledgeDashboard() {
  const { message } = App.useApp();
  const [data, setData] = useState<MetricsAggregation | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async (refresh = false) => {
    if (refresh) setRefreshing(true);
    else setLoading(true);
    try {
      setData(await getMetricsAggregation(refresh));
    } catch (e) {
      message.error(e instanceof Error ? e.message : '聚合数据加载失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [message]);

  useEffect(() => {
    void load(false);
  }, [load]);

  const kpis = [
    { label: '知识总量', value: data?.totalKnowledge ?? '-' },
    {
      label: '近 30 天检索',
      value: `${data?.totalRetrievals ?? '-'}（引用 ${data?.totalCitations ?? '-'} 次）`,
    },
    {
      label: '有用率',
      value: `${pct(data?.usefulnessRate)}（${data?.usefulCount ?? '-'} 有用 / ${data?.uselessCount ?? '-'} 无用）`,
    },
    { label: '召回率', value: pct(data?.recallRate) },
    { label: '疑似过期', value: `${data?.staleKnowledgeCount ?? '-'}（阈值 stale<0.3，τ=180d）` },
  ];

  const staleColumns: ColumnsType<StaleKnowledgeItem> = [
    {
      title: 'chunkId',
      dataIndex: 'chunkId',
      render: (v: string) => v.slice(0, 12) + '…',
      ellipsis: true,
    },
    { title: 'docId', dataIndex: 'docId' },
    { title: '领域', dataIndex: 'domainTag' },
    { title: '子领域', dataIndex: 'subDomainTag' },
    { title: '创建人', dataIndex: 'createdBy' },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      render: (v: string | null) => (v ? new Date(v).toLocaleDateString() : '-'),
    },
    { title: '近 30 天检索', dataIndex: 'recentRetrievals' },
    {
      title: 'stale_score',
      dataIndex: 'staleScore',
      render: (v: number) => (
        <Tag color={v < 0.15 ? 'red' : 'orange'}>{v.toFixed(3)}</Tag>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Button
          type="primary"
          icon={<ReloadOutlined />}
          onClick={() => void load(true)}
          loading={refreshing}
          style={{ marginRight: 8 }}
        >
          立即聚合
        </Button>
        <Typography.Text type="secondary">
          每日 02:00 自动聚合（时间衰减 τ=180 天 + 30 天使用频率）
        </Typography.Text>
      </div>
      {data?.aggregatedAt === null && (
        <Alert
          type="info"
          showIcon
          style={{ marginBottom: 12 }}
          description={data.message || '尚未聚合，点击“立即聚合”查看'}
        />
      )}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        {kpis.map((k) => (
          <Card key={k.label} style={{ minWidth: 200, flex: 1 }}>
            <Statistic title={k.label} value={k.value} />
          </Card>
        ))}
      </div>
      <Typography.Title level={4}>疑似过期知识（stale_score &lt; 0.3）</Typography.Title>
      <Table<StaleKnowledgeItem>
        rowKey="chunkId"
        size="small"
        columns={staleColumns}
        dataSource={data?.staleKnowledge ?? []}
        loading={loading}
        pagination={{ pageSize: 10 }}
        locale={{ emptyText: '暂无疑似过期知识' }}
      />
    </div>
  );
}

/** 知识治理 Tab：知识看板 + 冲突审核两个子页。 */
export default function GovernPanel() {
  const [active, setActive] = useState('dashboard');
  return (
    <div>
      <Typography.Title level={3}>知识治理</Typography.Title>
      <Tabs
        activeKey={active}
        onChange={setActive}
        items={[
          { key: 'dashboard', label: '知识看板', children: <KnowledgeDashboard /> },
          { key: 'conflicts', label: '冲突审核', children: <ConflictReviewPanel /> },
        ]}
      />
    </div>
  );
}
