'use client';

import { useParams } from 'next/navigation';
import { Typography } from 'antd';
import ResultsPanel from '@/components/results/ResultsPanel';

export default function ResultsPage() {
  const params = useParams<{ docId: string }>();

  return (
    <div>
      <Typography.Title level={3}>结构化结果</Typography.Title>
      <ResultsPanel docId={params.docId} />
    </div>
  );
}
