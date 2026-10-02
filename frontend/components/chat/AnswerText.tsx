'use client';

import { Popover, Tag, Typography } from 'antd';
import type { Source } from '@/lib/types';

interface Props {
  text: string;
  sources: Source[];
}

/** 将回答文本中的 [1][2] 引用渲染为可点击的上标角标 */
export default function AnswerText({ text, sources }: Props) {
  const parts: React.ReactNode[] = [];
  const re = /\[(\d+)\]/g;
  let last = 0;
  let m: RegExpExecArray | null;
  let key = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) {
      parts.push(<span key={key++}>{text.slice(last, m.index)}</span>);
    }
    const idx = Number(m[1]);
    const source = sources.find((s) => s.index === idx);
    if (source) {
      parts.push(
        <Popover
          key={key++}
          title={
            <span>
              来源 [{source.index}] {source.filename}
              {source.pageNo !== null && (
                <Tag style={{ marginLeft: 8 }}>第 {source.pageNo} 页</Tag>
              )}
            </span>
          }
          content={
            <div style={{ maxWidth: 420 }}>
              <Typography.Paragraph style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
                {source.snippet}
              </Typography.Paragraph>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                文档 ID：{source.docId} ｜ 版本：v{source.versionNo} ｜ 相关度：
                {source.score.toFixed(3)}
              </Typography.Text>
            </div>
          }
        >
          <sup
            style={{
              color: '#1677ff',
              cursor: 'pointer',
              fontWeight: 600,
              padding: '0 2px',
            }}
          >
            [{idx}]
          </sup>
        </Popover>,
      );
    } else {
      parts.push(<span key={key++}>{m[0]}</span>);
    }
    last = m.index + m[0].length;
  }
  if (last < text.length) {
    parts.push(<span key={key++}>{text.slice(last)}</span>);
  }
  return (
    <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
      {parts}
    </Typography.Paragraph>
  );
}
