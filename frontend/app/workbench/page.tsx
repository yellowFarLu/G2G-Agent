'use client';

import { useState } from 'react';
import { Card, Tabs, Typography } from 'antd';
import ReviewCasesTab from '@/components/workbench/ReviewCasesTab';
import HumanTasksTab from '@/components/workbench/HumanTasksTab';
import VersionHistoryTab from '@/components/workbench/VersionHistoryTab';

export default function WorkbenchPage() {
  const [activeTab, setActiveTab] = useState('review');

  return (
    <div>
      <Typography.Title level={3}>人工工作台</Typography.Title>
      <Card>
        <Tabs
          activeKey={activeTab}
          onChange={setActiveTab}
          items={[
            { key: 'review', label: '复核案件', children: <ReviewCasesTab /> },
            {
              key: 'human',
              label: '人工任务',
              children: <HumanTasksTab onGoReview={() => setActiveTab('review')} />,
            },
            { key: 'versions', label: '历史版本', children: <VersionHistoryTab /> },
          ]}
        />
      </Card>
    </div>
  );
}
