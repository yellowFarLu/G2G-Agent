'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Alert,
  App,
  Avatar,
  Button,
  Card,
  Empty,
  Input,
  List,
  Skeleton,
  Space,
  Tag,
  Typography,
} from 'antd';
import {
  LoadingOutlined,
  PlusOutlined,
  RobotOutlined,
  SendOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { getChatMessages, listChatSessions } from '@/lib/api';
import { chatStream } from '@/lib/sse';
import { getSettings } from '@/lib/settings';
import type { ChatStage, Source } from '@/lib/types';
import AnswerText from '@/components/chat/AnswerText';

interface ChatMsg {
  role: 'user' | 'assistant';
  content: string;
  sources: Source[];
  stage: ChatStage | null;
  rewrittenQuery?: string;
  blocked?: string;
  error?: string;
  streaming?: boolean;
}

const STAGE_LABEL: Record<ChatStage, string> = {
  rewriting: '改写中',
  retrieving: '检索中',
  generating: '生成中',
  fallback: '兜底应答',
};

const STAGE_ORDER: ChatStage[] = ['rewriting', 'retrieving', 'generating', 'fallback'];

function emptyAssistant(): ChatMsg {
  return { role: 'assistant', content: '', sources: [], stage: null, streaming: true };
}

export default function ChatPanel() {
  const { message } = App.useApp();
  const [sessions, setSessions] = useState<{ sessionId: string; label: string }[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement | null>(null);
  const sessionIdRef = useRef<string | null>(null);

  const loadSessions = useCallback(async () => {
    setSessionsLoading(true);
    try {
      const raw = await listChatSessions();
      const list = raw
        .map((s) => {
          const id = typeof s.sessionId === 'string' ? s.sessionId : null;
          if (!id) return null;
          const title =
            typeof s.title === 'string' && s.title
              ? s.title
              : typeof s.lastQuestion === 'string' && s.lastQuestion
                ? (s.lastQuestion as string)
                : id.slice(0, 8);
          return { sessionId: id, label: title };
        })
        .filter((x): x is { sessionId: string; label: string } => x !== null);
      setSessions(list);
    } catch {
      setSessions([]);
    } finally {
      setSessionsLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadSessions();
  }, [loadSessions]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const openSession = async (sid: string) => {
    abortRef.current?.abort();
    setSending(false);
    setSessionId(sid);
    sessionIdRef.current = sid;
    setHistoryLoading(true);
    setMessages([]);
    try {
      const raw = await getChatMessages(sid);
      const msgs: ChatMsg[] = raw
        .map((m): ChatMsg | null => {
          const role = typeof m.role === 'string' ? m.role : '';
          const content = typeof m.content === 'string' ? m.content : '';
          if (role === 'user') return { role: 'user', content, sources: [], stage: null };
          if (role === 'assistant')
            return { role: 'assistant', content, sources: [], stage: null };
          return null;
        })
        .filter((x): x is ChatMsg => x !== null);
      setMessages(msgs);
    } catch (e) {
      message.error(e instanceof Error ? e.message : '历史消息加载失败');
    } finally {
      setHistoryLoading(false);
    }
  };

  const newSession = () => {
    abortRef.current?.abort();
    setSending(false);
    setSessionId(null);
    sessionIdRef.current = null;
    setMessages([]);
  };

  const updateLastAssistant = (fn: (m: ChatMsg) => ChatMsg) => {
    setMessages((prev) => {
      const next = [...prev];
      const lastIdx = next.length - 1;
      if (lastIdx >= 0 && next[lastIdx].role === 'assistant') {
        next[lastIdx] = fn(next[lastIdx]);
      }
      return next;
    });
  };

  const send = () => {
    const question = input.trim();
    if (!question || sending) return;
    setInput('');
    setSending(true);
    setMessages((prev) => [...prev, { role: 'user', content: question, sources: [], stage: null }, emptyAssistant()]);

    const settings = getSettings();
    abortRef.current = chatStream(
      {
        question,
        domain: settings.domain || undefined,
        subDomain: settings.subDomain || undefined,
        identity: settings.identity || undefined,
        sessionId: sessionIdRef.current ?? undefined,
      },
      (name, data) => {
        switch (name) {
          case 'session': {
            const sid = (data as { sessionId?: string }).sessionId;
            if (sid) {
              sessionIdRef.current = sid;
              setSessionId(sid);
            }
            break;
          }
          case 'stage': {
            const d = data as { stage?: ChatStage; rewrittenQuery?: string };
            updateLastAssistant((m) => ({
              ...m,
              stage: d.stage ?? null,
              rewrittenQuery: d.rewrittenQuery ?? m.rewrittenQuery,
            }));
            break;
          }
          case 'sources': {
            if (Array.isArray(data)) {
              updateLastAssistant((m) => ({ ...m, sources: data as Source[] }));
            }
            break;
          }
          case 'delta': {
            const text = (data as { text?: string }).text ?? '';
            updateLastAssistant((m) => ({ ...m, content: m.content + text }));
            break;
          }
          case 'blocked': {
            const msg = (data as { message?: string }).message ?? '请求被拦截';
            updateLastAssistant((m) => ({ ...m, blocked: msg, streaming: false, stage: null }));
            setSending(false);
            break;
          }
          case 'error': {
            const msg = (data as { message?: string }).message ?? '对话出错';
            updateLastAssistant((m) => ({ ...m, error: msg, streaming: false, stage: null }));
            setSending(false);
            break;
          }
          case 'done': {
            updateLastAssistant((m) => ({ ...m, streaming: false, stage: null }));
            setSending(false);
            void loadSessions();
            break;
          }
          default:
            break;
        }
      },
    );
  };

  useEffect(() => {
    return () => abortRef.current?.abort();
  }, []);

  return (
    <div style={{ display: 'flex', gap: 16, height: 'calc(100vh - 160px)' }}>
      <Card
        title="会话列表"
        style={{ width: 260, flexShrink: 0, overflow: 'auto' }}
        extra={
          <Button size="small" icon={<PlusOutlined />} onClick={newSession}>
            新建会话
          </Button>
        }
      >
        {sessionsLoading ? (
          <Skeleton active paragraph={{ rows: 4 }} />
        ) : sessions.length === 0 ? (
          <Empty description="暂无历史会话" image={Empty.PRESENTED_IMAGE_SIMPLE} />
        ) : (
          <List
            dataSource={sessions}
            renderItem={(s) => (
              <List.Item
                style={{
                  cursor: 'pointer',
                  background: s.sessionId === sessionId ? '#e6f4ff' : undefined,
                  padding: '8px 12px',
                  borderRadius: 4,
                }}
                onClick={() => void openSession(s.sessionId)}
              >
                <Typography.Text ellipsis style={{ fontSize: 13 }}>
                  {s.label}
                </Typography.Text>
              </List.Item>
            )}
          />
        )}
      </Card>

      <Card
        title="对话"
        style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}
        styles={{ body: { flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' } }}
      >
        <div style={{ flex: 1, overflow: 'auto', paddingRight: 8 }}>
          {historyLoading ? (
            <Skeleton active paragraph={{ rows: 6 }} />
          ) : messages.length === 0 ? (
            <Empty
              style={{ marginTop: 120 }}
              description={
                <span>
                  暂无对话内容
                  <br />
                  在下方输入问题，开始与知识库对话
                </span>
              }
            />
          ) : (
            messages.map((m, idx) => (
              <div
                key={idx}
                style={{
                  display: 'flex',
                  justifyContent: m.role === 'user' ? 'flex-end' : 'flex-start',
                  marginBottom: 16,
                }}
              >
                {m.role === 'assistant' && <Avatar icon={<RobotOutlined />} style={{ marginRight: 8, flexShrink: 0 }} />}
                <div
                  style={{
                    maxWidth: '75%',
                    background: m.role === 'user' ? '#1677ff' : '#fff',
                    color: m.role === 'user' ? '#fff' : undefined,
                    border: m.role === 'assistant' ? '1px solid #f0f0f0' : undefined,
                    borderRadius: 8,
                    padding: '10px 14px',
                  }}
                >
                  {m.role === 'assistant' && m.streaming && (m.stage || !m.content) && (
                    <Space style={{ marginBottom: m.content ? 8 : 0 }}>
                      <LoadingOutlined spin />
                      {STAGE_ORDER.map((st) => (
                        <Tag key={st} color={m.stage === st ? 'processing' : 'default'}>
                          {STAGE_LABEL[st]}
                        </Tag>
                      ))}
                      {m.rewrittenQuery && (
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          改写：{m.rewrittenQuery}
                        </Typography.Text>
                      )}
                    </Space>
                  )}
                  {m.role === 'assistant' ? (
                    <>
                      <AnswerText text={m.content} sources={m.sources} />
                      {m.streaming && m.content && <LoadingOutlined spin style={{ marginLeft: 6 }} />}
                      {m.sources.length > 0 && !m.streaming && (
                        <div style={{ marginTop: 8, borderTop: '1px dashed #eee', paddingTop: 6 }}>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            引用来源：
                          </Typography.Text>
                          {m.sources.map((s) => (
                            <Tag key={s.index} style={{ fontSize: 12 }}>
                              [{s.index}] {s.filename}
                              {s.pageNo !== null ? ` P${s.pageNo}` : ''}
                            </Tag>
                          ))}
                        </div>
                      )}
                      {m.blocked && (
                        <Alert type="warning" showIcon title="请求被拦截" description={m.blocked} style={{ marginTop: 8 }} />
                      )}
                      {m.error && (
                        <Alert type="error" showIcon title="对话出错" description={m.error} style={{ marginTop: 8 }} />
                      )}
                    </>
                  ) : (
                    <span style={{ whiteSpace: 'pre-wrap' }}>{m.content}</span>
                  )}
                </div>
                {m.role === 'user' && <Avatar icon={<UserOutlined />} style={{ marginLeft: 8, flexShrink: 0 }} />}
              </div>
            ))
          )}
          <div ref={bottomRef} />
        </div>

        <div style={{ borderTop: '1px solid #f0f0f0', paddingTop: 12 }}>
          <Space.Compact style={{ width: '100%' }}>
            <Input.TextArea
              value={input}
              onChange={(e) => setInput(e.target.value)}
              placeholder="输入问题，回车发送（Shift+回车换行）"
              autoSize={{ minRows: 1, maxRows: 6 }}
              onPressEnter={(e) => {
                if (!e.shiftKey) {
                  e.preventDefault();
                  send();
                }
              }}
            />
            <Button type="primary" icon={<SendOutlined />} loading={sending} onClick={send}>
              发送
            </Button>
          </Space.Compact>
        </div>
      </Card>
    </div>
  );
}
