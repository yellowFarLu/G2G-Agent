'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Badge, Button, Card, Descriptions, Select, Space, Spin, Tabs, Typography } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import { getDocument, getLineage } from '@/lib/api';
import type { DocumentView, DocVersion, ExtractedField } from '@/lib/types';
import DocStatusTag from '@/components/common/DocStatusTag';
import FieldTable from '@/components/results/FieldTable';
import FieldLineageDrawer from '@/components/results/FieldLineageDrawer';
import ConflictReviewPanel from '@/components/govern/ConflictReviewPanel';

export default function ResultsPanel({ docId }: { docId: string }) {
  const [doc, setDoc] = useState<DocumentView | null>(null);
  const [versions, setVersions] = useState<DocVersion[]>([]);
  const [fields, setFields] = useState<ExtractedField[]>([]);
  const [selectedVersion, setSelectedVersion] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [lineageFieldKey, setLineageFieldKey] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [d, lineage] = await Promise.all([getDocument(docId), getLineage(docId)]);
      setDoc(d);
      setVersions(lineage.versions ?? []);
      setFields(lineage.fields ?? []);
      const published = (lineage.versions ?? []).find((v) => v.status === 'PUBLISHED');
      setSelectedVersion(published?.versionNo ?? (lineage.versions?.[0]?.versionNo ?? null));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [docId]);

  useEffect(() => {
    void load();
  }, [load]);

  const visibleFields = useMemo(
    () => (selectedVersion === null ? fields : fields.filter((f) => f.versionNo === selectedVersion)),
    [fields, selectedVersion],
  );

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: 80 }}>
        <Spin size="large" description="结构化结果加载中…">
          <div style={{ width: 200, height: 80 }} />
        </Spin>
      </div>
    );
  }

  if (error) {
    return (
      <Alert
        type="error"
        showIcon
        title="结构化结果加载失败"
        description={error}
        action={
          <Button size="small" onClick={load}>
            重试
          </Button>
        }
      />
    );
  }

  const tabItems = [
    {
      key: 'fields',
      label: '结构化字段',
      children: (
        <FieldTable
          fields={visibleFields}
          loading={false}
          onShowLineage={(k) => setLineageFieldKey(k)}
        />
      ),
    },
    {
      key: 'conflicts',
      label: '冲突',
      children: <ConflictReviewPanel />,
    },
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title={
          <Space>
            <span>{doc?.filename ?? docId}</span>
            {doc && <DocStatusTag status={doc.status} />}
          </Space>
        }
        extra={
          <Space>
            <Typography.Text>版本：</Typography.Text>
            <Select
              style={{ width: 220 }}
              value={selectedVersion}
              onChange={(v) => setSelectedVersion(v)}
              placeholder="全部版本"
              allowClear
              options={versions.map((v) => ({
                value: v.versionNo,
                label: `v${v.versionNo}（${v.status}）${v.changeSummary ? ` - ${v.changeSummary}` : ''}`,
              }))}
            />
            <Button icon={<ReloadOutlined />} onClick={load}>
              刷新
            </Button>
          </Space>
        }
      >
        {doc && (
          <Descriptions size="small" column={4}>
            <Descriptions.Item label="文档 ID">
              <Typography.Text code>{doc.id}</Typography.Text>
            </Descriptions.Item>
            <Descriptions.Item label="类型">{doc.docType}</Descriptions.Item>
            <Descriptions.Item label="上传时间">{doc.createdAt}</Descriptions.Item>
            <Descriptions.Item label="分块">
              父 {doc.parentCount} ／ 子 {doc.childCount}
            </Descriptions.Item>
            {doc.error && (
              <Descriptions.Item label="错误" span={4}>
                <Typography.Text type="danger">{doc.error}</Typography.Text>
              </Descriptions.Item>
            )}
          </Descriptions>
        )}
      </Card>

      <Card>
        <Badge.Ribbon text={`共 ${visibleFields.length} 个字段`} color="blue">
          <Tabs items={tabItems} />
        </Badge.Ribbon>
      </Card>

      <FieldLineageDrawer
        docId={docId}
        fieldKey={lineageFieldKey}
        onClose={() => setLineageFieldKey(null)}
      />
    </Space>
  );
}
