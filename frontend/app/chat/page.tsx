'use client';

import { Typography } from 'antd';
import ChatPanel from '@/components/chat/ChatPanel';

export default function ChatPage() {
  return (
    <div>
      <Typography.Title level={3}>对话</Typography.Title>
      <ChatPanel />
    </div>
  );
}
