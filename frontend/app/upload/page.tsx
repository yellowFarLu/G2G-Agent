'use client';

import { useState } from 'react';
import { Typography } from 'antd';
import UploadPanel from '@/components/upload/UploadPanel';
import RecentDocuments from '@/components/upload/RecentDocuments';

export default function UploadPage() {
  const [tick, setTick] = useState(0);

  return (
    <div>
      <Typography.Title level={3}>材料上传</Typography.Title>
      <UploadPanel onUploaded={() => setTick((t) => t + 1)} />
      <RecentDocuments key={tick} />
    </div>
  );
}
