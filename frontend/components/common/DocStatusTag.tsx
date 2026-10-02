'use client';

import { Tag } from 'antd';

const STATUS_MAP: Record<string, { color: string; label: string }> = {
  PARSING: { color: 'processing', label: '解析中' },
  READY: { color: 'success', label: '就绪' },
  FAILED: { color: 'error', label: '失败' },
  EXTRACTING: { color: 'processing', label: '抽取中' },
  AI_SKIPPED: { color: 'warning', label: 'AI 跳过' },
};

export default function DocStatusTag({ status }: { status: string }) {
  const meta = STATUS_MAP[status] ?? { color: 'default', label: status };
  return <Tag color={meta.color}>{meta.label}</Tag>;
}
