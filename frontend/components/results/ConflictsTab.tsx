'use client';

import { useCallback, useEffect, useState } from 'react';
import { Alert, Button, Card, Col, Empty, Row, Skeleton, Tag, Typography } from 'antd';
import { getConflictDiff, listConflicts } from '@/lib/api';
import type { ConflictChunkView, ConflictDiff, ConflictItem } from '@/lib/types';

function ChunkCard({ title, chunk }: { title: string; chunk: ConflictChunkView }) {
  return (
    <Card size="small" title={title} styles={{ body: { maxHeight: 320, overflow: 'auto' } }}>
      <div style={{ marginBottom: 8 }}>
        {chunk.sourceFilename && (
          <Tag color="blue">{chunk.sourceFilename}</Tag>
        )}
        {chunk.domainTag && <Tag>{chunk.domainTag}</Tag>}
        {chunk.subDomainTag && <Tag>{chunk.subDomainTag}</Tag>}
        {chunk.version !== undefined && <Tag color="purple">v{chunk.version}</Tag>}
      </div>
      <pre style={{ fontSize: 12, whiteSpace: 'pre-wrap', margin: 0 }}>
        {chunk.content || '（无内容）'}
      </pre>
    </Card>
  );
}

export default function ConflictsTab({ docId }: { docId: string }) {
  const [conflicts, setConflicts] = useState<ConflictItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [diffs, setDiffs] = useState<Record<string, ConflictDiff>>({});
  const [diffLoading, setDiffLoading] = useState<Record<string, boolean>>({});

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // 后端冲突实体无 docId 字段（仅 chunkIdA/B + domainTag），列表不按文档过滤
      setConflicts(await listConflicts('DETECTED'));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [docId]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadDiff = async (id: number) => {
    const key = String(id);
    setDiffLoading((prev) => ({ ...prev, [key]: true }));
    try {
      const d = await getConflictDiff(key);
      setDiffs((prev) => ({ ...prev, [key]: d }));
    } catch {
      // diff 加载失败时保持折叠状态
    } finally {
      setDiffLoading((prev) => ({ ...prev, [key]: false }));
    }
  };

  if (loading) return <Skeleton active paragraph={{ rows: 4 }} />;
  if (error) {
    return (
      <Alert
        type="error"
        showIcon
        title="冲突列表加载失败"
        description={error}
        action={
          <Button size="small" onClick={load}>
            重试
          </Button>
        }
      />
    );
  }
  if (conflicts.length === 0) {
    return <Empty description="暂无检测到的知识冲突" />;
  }

  return (
    <div>
      <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        共 {conflicts.length} 条待处理冲突（冲突实体不含文档维度，展示全部 DETECTED 条目）
      </Typography.Text>
      {conflicts.map((c, idx) => {
        const key = c.id !== undefined ? String(c.id) : `idx-${idx}`;
        const diff = diffs[key];
        return (
          <Card
            key={key}
            size="small"
            style={{ marginBottom: 12 }}
            title={
              <span>
                冲突 #{key}
                {c.similarity !== undefined && (
                  <Tag style={{ marginLeft: 8 }}>相似度 {c.similarity.toFixed(2)}</Tag>
                )}
                {c.domainTag && <Tag>{c.domainTag}</Tag>}
                {c.status && <Tag color="orange">{c.status}</Tag>}
              </span>
            }
            extra={
              !diff &&
              c.id !== undefined && (
                <Button size="small" loading={diffLoading[key]} onClick={() => void loadDiff(c.id!)}>
                  查看对比
                </Button>
              )
            }
          >
            {diff ? (
              <Row gutter={12}>
                <Col span={12}>
                  <ChunkCard title="版本 A" chunk={diff.chunkA} />
                </Col>
                <Col span={12}>
                  <ChunkCard title="版本 B" chunk={diff.chunkB} />
                </Col>
              </Row>
            ) : (
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                chunkA={c.chunkIdA ?? '-'} ｜ chunkB={c.chunkIdB ?? '-'}
              </Typography.Text>
            )}
          </Card>
        );
      })}
    </div>
  );
}
