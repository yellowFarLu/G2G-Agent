'use client';

import { useParams } from 'next/navigation';
import { Typography } from 'antd';
import TaskDetail from '@/components/tasks/TaskDetail';

export default function TaskDetailPage() {
  const params = useParams<{ taskId: string }>();
  const taskId = params.taskId;

  return (
    <div>
      <Typography.Title level={3}>任务详情</Typography.Title>
      <TaskDetail taskId={taskId} />
    </div>
  );
}
