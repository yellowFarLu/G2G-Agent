'use client';

import { useEffect, useState } from 'react';
import { Alert, Button, Card, Drawer, Empty, Skeleton, Tag, Timeline, Typography } from 'antd';
import { getFieldLineage } from '@/lib/api';
import type { FieldLineageView } from '@/lib/types';

interface Props {
  docId: string;
  fieldKey: string | null;
  onClose: () => void;
}

function renderUnknown(v: unknown): string {
  if (v === null || v === undefined) return '';
  if (typeof v === 'string') return v;
  try {
    return JSON.stringify(v, null, 2);
  } catch {
    return String(v);
  }
}

export default function FieldLineageDrawer({ docId, fieldKey, onClose }: Props) {
  const [data, setData] = useState<FieldLineageView | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = async () => {
    if (!fieldKey) return;
    setLoading(true);
    setError(null);
    try {
      setData(await getFieldLineage(docId, fieldKey));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    setData(null);
    if (fieldKey) void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId, fieldKey]);

  return (
    <Drawer
      title={`字段来源：${fieldKey ?? ''}`}
      size={560}
      open={fieldKey !== null}
      onClose={onClose}
    >
      {loading && <Skeleton active paragraph={{ rows: 6 }} />}
      {error && (
        <Alert
          type="error"
          showIcon
          title="来源信息加载失败"
          description={error}
          action={
            <Button size="small" onClick={load}>
              重试
            </Button>
          }
        />
      )}
      {!loading && !error && data && (
        <>
          {data.current ? (
            <Card
              size="small"
              title={
                <span>
                  当前取值（版本 v{data.current.versionNo}
                  {data.current.pageNo !== null && ` ｜ 第 ${data.current.pageNo} 页`}）
                </span>
              }
              style={{ marginBottom: 16, borderColor: '#1677ff' }}
            >
              <Typography.Paragraph strong>{data.current.valueText}</Typography.Paragraph>
              {data.current.snippet && (
                <Card
                  size="small"
                  style={{ background: '#fffbe6', borderColor: '#ffe58f', marginBottom: 8 }}
                >
                  <Typography.Text style={{ fontSize: 12 }}>{data.current.snippet}</Typography.Text>
                </Card>
              )}
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                来源：{data.current.source} ｜ 置信度：{data.current.confidence.toFixed(2)} ｜ 更新时间：
                {data.current.updatedAt}
              </Typography.Text>
            </Card>
          ) : (
            <Empty description="该字段当前无取值" />
          )}

          <Typography.Title level={5}>历史版本</Typography.Title>
          {data.history.length === 0 ? (
            <Empty description="暂无历史版本" image={Empty.PRESENTED_IMAGE_SIMPLE} />
          ) : (
            <Timeline
              items={data.history.map((h, idx) => {
                const rec = (h ?? {}) as Record<string, unknown>;
                const versionNo = typeof rec.versionNo === 'number' ? rec.versionNo : idx;
                const valueText = typeof rec.valueText === 'string' ? rec.valueText : renderUnknown(rec.valueText);
                const createdAt = typeof rec.createdAt === 'string' ? rec.createdAt : '';
                const editedBy =
                  typeof rec.editedBy === 'string'
                    ? rec.editedBy
                    : typeof rec.createdBy === 'string'
                      ? rec.createdBy
                      : '';
                return {
                  key: idx,
                  children: (
                    <div>
                      <Tag>v{versionNo}</Tag>
                      <Typography.Text>{valueText || '（空值）'}</Typography.Text>
                      <div>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {createdAt}
                          {editedBy ? ` ｜ ${editedBy}` : ''}
                        </Typography.Text>
                      </div>
                    </div>
                  ),
                };
              })}
            />
          )}
        </>
      )}
    </Drawer>
  );
}
