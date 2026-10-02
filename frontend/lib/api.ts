'use client';

import { getSettings } from './settings';
import type {
  BackendErrorBody,
  ChatMessageItem,
  ChatSessionItem,
  ConflictDiff,
  ConflictItem,
  DocumentView,
  DocVersion,
  EventView,
  FieldLineageView,
  HumanTaskResolveRequest,
  HumanTaskView,
  IdentityProfile,
  IdentityUpdateAck,
  IdentityUpdateRequest,
  LineageView,
  ReviewCase,
  ReviewCaseResolveRequest,
  Source,
  StepView,
  TaskView,
  VersionDiff,
} from './types';

function authHeaders(): Record<string, string> {
  const settings = getSettings();
  const headers: Record<string, string> = {
    'X-User-Id': settings.userId || 'anonymous',
  };
  if (settings.identity) {
    headers['X-Business-Identity'] = settings.identity;
  }
  return headers;
}

async function handleResponse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`;
    try {
      const body = (await res.json()) as BackendErrorBody;
      if (body && typeof body.message === 'string' && body.message) {
        message = body.message;
      }
    } catch {
      // 忽略解析失败，使用默认消息
    }
    throw new Error(message);
  }
  return (await res.json()) as T;
}

async function request<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const headers: Record<string, string> = {
    ...authHeaders(),
    ...((init?.headers as Record<string, string> | undefined) ?? {}),
  };
  const res = await fetch(path, { ...init, headers });
  return handleResponse<T>(res);
}

function jsonBody(body: unknown): RequestInit {
  return {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  };
}

// ============ 文档 ============
export async function uploadDocument(
  file: File,
  domain?: string,
  subDomain?: string,
): Promise<DocumentView> {
  const form = new FormData();
  form.append('file', file);
  if (domain) form.append('domain', domain);
  if (subDomain) form.append('subDomain', subDomain);
  const res = await fetch('/api/documents', {
    method: 'POST',
    headers: authHeaders(),
    body: form,
  });
  return handleResponse<DocumentView>(res);
}

export function listDocuments(): Promise<DocumentView[]> {
  return request<DocumentView[]>('/api/documents');
}

export function getDocument(id: string): Promise<DocumentView> {
  return request<DocumentView>(`/api/documents/${encodeURIComponent(id)}`);
}

export async function deleteDocument(id: string): Promise<void> {
  const res = await fetch(`/api/documents/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok) {
    await handleResponse<unknown>(res);
  }
}

// ============ 任务 ============
export function listTasks(params?: { status?: string; mine?: boolean }): Promise<TaskView[]> {
  const search = new URLSearchParams();
  if (params?.status) search.set('status', params.status);
  if (params?.mine) search.set('mine', 'true');
  const qs = search.toString();
  return request<TaskView[]>(`/api/tasks${qs ? `?${qs}` : ''}`);
}

export function getTask(taskId: string): Promise<TaskView> {
  return request<TaskView>(`/api/tasks/${encodeURIComponent(taskId)}`);
}

export function getTaskSteps(taskId: string): Promise<StepView[]> {
  return request<StepView[]>(`/api/tasks/${encodeURIComponent(taskId)}/steps`);
}

export function getTaskEvents(taskId: string): Promise<EventView[]> {
  return request<EventView[]>(`/api/tasks/${encodeURIComponent(taskId)}/events`);
}

export function getTaskHumanTasks(taskId: string): Promise<HumanTaskView[]> {
  return request<HumanTaskView[]>(`/api/tasks/${encodeURIComponent(taskId)}/human-tasks`);
}

function taskAction(taskId: string, action: string, body?: unknown): Promise<TaskView> {
  return request<TaskView>(
    `/api/tasks/${encodeURIComponent(taskId)}/${action}`,
    body === undefined ? { method: 'POST' } : jsonBody(body),
  );
}

export function suspendTask(taskId: string, expectedVersion?: number): Promise<TaskView> {
  return taskAction(taskId, 'suspend', expectedVersion === undefined ? {} : { expectedVersion });
}

export function resumeTask(taskId: string): Promise<TaskView> {
  return taskAction(taskId, 'resume');
}

export function cancelTask(taskId: string, expectedVersion?: number): Promise<TaskView> {
  return taskAction(taskId, 'cancel', expectedVersion === undefined ? {} : { expectedVersion });
}

export function replayTask(taskId: string): Promise<TaskView> {
  return taskAction(taskId, 'replay');
}

// ============ 人工任务 ============
export function claimHumanTask(id: string, lockVersion?: number): Promise<HumanTaskView> {
  return request<HumanTaskView>(
    `/api/human-tasks/${encodeURIComponent(id)}/claim`,
    jsonBody(lockVersion === undefined ? {} : { lockVersion }),
  );
}

export function resolveHumanTask(id: string, body: HumanTaskResolveRequest): Promise<TaskView> {
  return request<TaskView>(`/api/human-tasks/${encodeURIComponent(id)}/resolve`, jsonBody(body));
}

// ============ 对话 ============
export function listChatSessions(): Promise<ChatSessionItem[]> {
  return request<ChatSessionItem[]>('/api/chat/sessions');
}

export function getChatMessages(sessionId: string): Promise<ChatMessageItem[]> {
  return request<ChatMessageItem[]>(`/api/chat/sessions/${encodeURIComponent(sessionId)}/messages`);
}

// ============ 血缘 / 版本 ============
export function getLineage(docId: string): Promise<LineageView> {
  return request<LineageView>(`/api/documents/${encodeURIComponent(docId)}/lineage`);
}

export function getFieldLineage(docId: string, fieldKey: string): Promise<FieldLineageView> {
  return request<FieldLineageView>(
    `/api/documents/${encodeURIComponent(docId)}/fields/${encodeURIComponent(fieldKey)}/lineage`,
  );
}

export function getVersions(docId: string): Promise<DocVersion[]> {
  return request<DocVersion[]>(`/api/documents/${encodeURIComponent(docId)}/versions`);
}

export function getVersionDiff(docId: string, a: number, b: number): Promise<VersionDiff> {
  return request<VersionDiff>(
    `/api/documents/${encodeURIComponent(docId)}/versions/${a}/diff/${b}`,
  );
}

// ============ 复核案件 ============
export function listReviewCases(params?: { status?: string; docId?: string }): Promise<ReviewCase[]> {
  const search = new URLSearchParams();
  if (params?.status) search.set('status', params.status);
  if (params?.docId) search.set('docId', params.docId);
  const qs = search.toString();
  return request<ReviewCase[]>(`/api/review-cases${qs ? `?${qs}` : ''}`);
}

export function getReviewCase(id: string): Promise<ReviewCase> {
  return request<ReviewCase>(`/api/review-cases/${encodeURIComponent(id)}`);
}

export function resolveReviewCase(id: number, body: ReviewCaseResolveRequest): Promise<ReviewCase> {
  return request<ReviewCase>(`/api/review-cases/${encodeURIComponent(String(id))}/resolve`, jsonBody(body));
}

// ============ 冲突 ============
export function listConflicts(status?: string): Promise<ConflictItem[]> {
  const qs = status ? `?status=${encodeURIComponent(status)}` : '';
  return request<ConflictItem[]>(`/api/conflicts${qs}`);
}

export function getConflictDiff(id: string): Promise<ConflictDiff> {
  return request<ConflictDiff>(`/api/conflicts/${encodeURIComponent(id)}/diff`);
}

// ============ 身份管理 ============
export function getIdentityProfile(userId: string): Promise<IdentityProfile> {
  return request<IdentityProfile>(`/api/admin/identity/${encodeURIComponent(userId)}`);
}

export function updateIdentityProfile(
  userId: string,
  body: IdentityUpdateRequest,
): Promise<IdentityUpdateAck> {
  return request<IdentityUpdateAck>(`/api/admin/identity/${encodeURIComponent(userId)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
}

export type { Source };
