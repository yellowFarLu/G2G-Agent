'use client';

import { Tag } from 'antd';

const STATUS_MAP: Record<string, { color: string; label: string }> = {
  PENDING: { color: 'default', label: '待执行' },
  RUNNING: { color: 'processing', label: '运行中' },
  WAITING_HUMAN: { color: 'warning', label: '等待人工' },
  SUSPENDED: { color: 'orange', label: '已暂停' },
  COMPLETED: { color: 'success', label: '已完成' },
  FAILED: { color: 'error', label: '失败' },
  CANCELLED: { color: 'default', label: '已取消' },
};

export default function TaskStatusTag({ status }: { status: string }) {
  const meta = STATUS_MAP[status] ?? { color: 'default', label: status };
  return <Tag color={meta.color}>{meta.label}</Tag>;
}
