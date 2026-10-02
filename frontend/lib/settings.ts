'use client';

import type { Domain, Identity, SubDomain } from './types';

export interface LocalSettings {
  userId: string;
  identity: Identity | '';
  domain: Domain | '';
  subDomain: SubDomain | '';
}

const KEYS = {
  userId: 'wikiagent.userId',
  identity: 'wikiagent.identity',
  domain: 'wikiagent.domain',
  subDomain: 'wikiagent.subDomain',
} as const;

export const DEFAULT_SETTINGS: LocalSettings = {
  userId: 'anonymous',
  identity: '',
  domain: '',
  subDomain: '',
};

export function getSettings(): LocalSettings {
  if (typeof window === 'undefined') return DEFAULT_SETTINGS;
  return {
    userId: window.localStorage.getItem(KEYS.userId) || DEFAULT_SETTINGS.userId,
    identity: (window.localStorage.getItem(KEYS.identity) as Identity | null) || '',
    domain: (window.localStorage.getItem(KEYS.domain) as Domain | null) || '',
    subDomain: (window.localStorage.getItem(KEYS.subDomain) as SubDomain | null) || '',
  };
}

export function saveSettings(settings: LocalSettings): void {
  if (typeof window === 'undefined') return;
  window.localStorage.setItem(KEYS.userId, settings.userId);
  window.localStorage.setItem(KEYS.identity, settings.identity);
  window.localStorage.setItem(KEYS.domain, settings.domain);
  window.localStorage.setItem(KEYS.subDomain, settings.subDomain);
  window.dispatchEvent(new Event('wikiagent-settings-changed'));
}

export const IDENTITY_OPTIONS: { value: Identity; label: string }[] = [
  { value: 'admin', label: '管理员' },
  { value: 'business', label: '业务' },
  { value: 'product', label: '产品' },
  { value: 'technology', label: '技术' },
  { value: 'test', label: '测试' },
];

export const DOMAIN_OPTIONS: { value: Domain; label: string }[] = [
  { value: 'industry_solution', label: '行业方案' },
  { value: 'merchant_center', label: '商家中心' },
  { value: 'pms', label: 'PMS' },
  { value: 'service_provider', label: '服务商' },
  { value: 'trunk_line', label: '干线' },
  { value: 'customs', label: '报关' },
  { value: 'settlement', label: '结算' },
  { value: 'first_mile', label: '头程' },
  { value: 'trajectory', label: '轨迹' },
];

export const SUB_DOMAIN_OPTIONS: { value: SubDomain; label: string }[] = [
  { value: 'product_doc', label: '产品文档' },
  { value: 'operation_manual', label: '操作手册' },
  { value: 'faq', label: 'FAQ' },
  { value: 'case_library', label: '案例库' },
  { value: 'rule_config', label: '规则配置' },
  { value: 'api_doc', label: 'API 文档' },
];

export const IDENTITY_LABELS: Record<string, string> = Object.fromEntries(
  IDENTITY_OPTIONS.map((o) => [o.value, o.label]),
);
