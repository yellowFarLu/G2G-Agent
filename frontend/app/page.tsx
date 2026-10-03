'use client';

import { Suspense } from 'react';
import { Spin } from 'antd';
import Console from '@/components/Console';

export default function Home() {
  return (
    <Suspense
      fallback={
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      }
    >
      <Console />
    </Suspense>
  );
}
