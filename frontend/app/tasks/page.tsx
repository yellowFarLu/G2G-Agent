'use client';

import { Typography } from 'antd';
import TaskTable from '@/components/tasks/TaskTable';

export default function TasksPage() {
  return (
    <div>
      <Typography.Title level={3}>任务中心</Typography.Title>
      <TaskTable />
    </div>
  );
}
