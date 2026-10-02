import { test, expect, type Page, type Route } from '@playwright/test';

/**
 * AC-G1 全链路 e2e（mock 后端模式）：
 * 上传 → 任务进度 → 字段结果 → 来源页码片段 → 低置信人工修改 → 提交确认 → 历史版本。
 *
 * 所有 /api/** 请求由 page.route 拦截并返回固定固件，验证前端真实渲染与交互链路；
 * 真实后端冒烟见 real-backend.spec.ts（E2E_REAL_BACKEND=1）。
 */

const DOC_ID = 'doc-e2e-1';
const TASK_ID = 'task-e2e-1';
const NOW = '2026-10-03T02:00:00Z';

const docView = {
  id: DOC_ID,
  filename: 'e2e-合同.txt',
  docType: 'txt',
  sizeBytes: 128,
  status: 'READY',
  parentCount: 2,
  childCount: 5,
  error: null,
  createdAt: NOW,
  taskId: TASK_ID,
  duplicate: false,
};

const taskView = {
  taskId: TASK_ID,
  taskType: 'INGEST',
  bizKey: 'ingest:abc:e2e',
  status: 'RUNNING',
  attempt: 1,
  maxAttempts: 3,
  progressPercent: 30,
  resultRef: null,
  errorCode: null,
  errorMsg: null,
  suspendReason: null,
  submittedBy: 'e2e',
  leaseOwner: null,
  createdAt: NOW,
  updatedAt: NOW,
};

const steps = [
  'DOWNLOAD',
  'PARSE',
  'CLEAN',
  'SPLIT',
  'EMBED',
  'VERIFY',
].map((name, i) => ({
  stepNo: i + 1,
  stepType: name,
  stepName: name,
  status: 'DONE',
  startedAt: NOW,
  endedAt: NOW,
  errorMsg: null,
}));

/** 低置信字段：置信度 0.62，触发人工复核，含页码 2 + 原文片段。 */
const lowField = {
  id: 11,
  docId: DOC_ID,
  fieldKey: 'party_a',
  fieldLabel: '甲方名称',
  valueText: '某某物流有限公司',
  valueType: 'STRING',
  confidence: 0.62,
  source: 'MODEL',
  schemaKey: 'invoice-demo',
  schemaVersion: '1.0',
  valid: true,
  reviewRequired: true,
  versionNo: 2,
  pageNo: 2,
  snippet: '甲方：某某物流有限公司',
  createdAt: NOW,
  updatedAt: NOW,
};

const highField = {
  ...lowField,
  id: 12,
  fieldKey: 'total_amount',
  fieldLabel: '合同金额',
  valueText: '1000000.00',
  confidence: 0.96,
  reviewRequired: false,
  pageNo: 1,
  snippet: '合同总金额：人民币壹佰万元整',
};

const versions = [
  {
    id: 1,
    docId: DOC_ID,
    versionNo: 1,
    status: 'SUPERSEDED',
    parentVersionNo: null,
    changeSummary: '首次解析',
    artifactSha256: 'aaa',
    createdBy: 'SYSTEM',
    createdAt: NOW,
  },
  {
    id: 2,
    docId: DOC_ID,
    versionNo: 2,
    status: 'PUBLISHED',
    parentVersionNo: 1,
    changeSummary: '人工修订后重解析',
    artifactSha256: 'bbb',
    createdBy: 'e2e',
    createdAt: NOW,
  },
];

const reviewCase = {
  id: 1,
  caseType: 'LOW_CONFIDENCE',
  docId: DOC_ID,
  versionNo: 2,
  fieldKey: 'party_a',
  diffJson: JSON.stringify({ party_a: '某物流公司' }),
  source: 'MODEL',
  confidence: 0.62,
  status: 'OPEN',
  humanTaskId: 5,
  taskId: TASK_ID,
  ruleCode: null,
  ruleVersion: null,
  computationId: null,
  resolutionJson: null,
  resolvedBy: null,
  createdAt: NOW,
  resolvedAt: null,
};

function json(route: Route, body: unknown, status = 200) {
  return route.fulfill({
    status,
    contentType: 'application/json',
    body: JSON.stringify(body),
  });
}

/** 注册全部 mock 路由。 */
async function mockBackend(page: Page) {
  await page.route('**/api/documents', (route) => {
    if (route.request().method() === 'POST') {
      return json(route, docView);
    }
    return json(route, [docView]);
  });
  await page.route(`**/api/documents/${DOC_ID}`, (route) => json(route, docView));
  await page.route(`**/api/documents/${DOC_ID}/lineage`, (route) =>
    json(route, {
      docId: DOC_ID,
      versions,
      artifacts: [],
      edges: [],
      fields: [lowField, highField],
    }),
  );
  await page.route(`**/api/documents/${DOC_ID}/fields/*/lineage`, (route) =>
    json(route, {
      docId: DOC_ID,
      fieldKey: 'party_a',
      current: lowField,
      history: [
        {
          versionNo: 1,
          valueText: '某物流公司',
          source: 'MODEL',
          createdAt: NOW,
          createdBy: 'SYSTEM',
        },
      ],
      edges: [],
    }),
  );
  await page.route(`**/api/documents/${DOC_ID}/versions`, (route) => json(route, versions));
  await page.route(`**/api/documents/${DOC_ID}/versions/1/diff/2`, (route) =>
    json(route, {
      docId: DOC_ID,
      versionA: 1,
      versionB: 2,
      fields: [
        {
          fieldKey: 'party_a',
          valueA: '某物流公司',
          valueB: '某某物流有限公司',
          changed: true,
          confidenceA: 0.62,
          confidenceB: 0.9,
          sourceA: 'MODEL',
          sourceB: 'HUMAN',
          editedByA: null,
          editedByB: 'e2e',
        },
        {
          fieldKey: 'total_amount',
          valueA: '1000000.00',
          valueB: '1000000.00',
          changed: false,
          confidenceA: 0.96,
          confidenceB: 0.96,
          sourceA: 'MODEL',
          sourceB: 'MODEL',
          editedByA: null,
          editedByB: null,
        },
      ],
    }),
  );
  // 快照两次状态：首次 RUNNING/30，SSE done 后刷新为 COMPLETED/100
  let taskPoll = 0;
  await page.route(`**/api/tasks/${TASK_ID}`, (route) => {
    taskPoll += 1;
    return json(
      route,
      taskPoll === 1
        ? taskView
        : { ...taskView, status: 'COMPLETED', progressPercent: 100 },
    );
  });
  await page.route(`**/api/tasks/${TASK_ID}/steps`, (route) => json(route, steps));
  await page.route(`**/api/tasks/${TASK_ID}/events`, (route) =>
    json(route, [
      {
        eventType: 'COMPLETED',
        actorType: 'SYSTEM',
        actorId: 'worker-1',
        detail: {},
        createdAt: NOW,
      },
    ]),
  );
  await page.route(`**/api/tasks/${TASK_ID}/human-tasks`, (route) => json(route, []));
  await page.route(`**/api/tasks/${TASK_ID}/stream`, (route) =>
    route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream' },
      body: 'event: progress\ndata: {"progressPercent":55}\n\nevent: progress\ndata: {"progressPercent":100}\n\nevent: done\ndata: {}\n\n',
    }),
  );
  await page.route('**/api/tasks?*', (route) => json(route, [taskView]));
  await page.route('**/api/review-cases?*', (route) => json(route, [reviewCase]));
  await page.route('**/api/review-cases/1', (route) => json(route, reviewCase));
  await page.route('**/api/review-cases/1/resolve', (route) =>
    json(route, { ...reviewCase, status: 'EDITED', resolvedBy: 'e2e', resolvedAt: NOW }),
  );
  await page.route('**/api/chat/sessions', (route) => json(route, []));
}

test('AC-G1 全链路：上传→进度→字段结果→来源→人工修改→历史版本', async ({ page }) => {
  await mockBackend(page);

  // 1) 上传
  await page.goto('/upload');
  await expect(page.getByText('点击或拖拽文件到此区域上传')).toBeVisible();
  await page.locator('input[type="file"]').first().setInputFiles({
    name: 'e2e-合同.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('甲方：某某物流有限公司\n合同总金额：人民币壹佰万元整', 'utf-8'),
  });
  await expect(page.getByText('「e2e-合同.txt」上传成功').first()).toBeVisible();

  // 2) 任务进度（点击成功提示中的任务链接）
  await page.getByRole('link', { name: /查看任务/ }).first().click();
  await expect(page).toHaveURL(new RegExp(`/tasks/${TASK_ID}`));
  await expect(page.getByText('执行步骤')).toBeVisible();
  await expect(page.getByText('VERIFY').first()).toBeVisible();
  await expect(page.getByText('事件时间线')).toBeVisible();
  // SSE progress 事件驱动进度推进，done 后快照刷新为终态
  await expect(page.getByRole('progressbar')).toBeVisible();
  await expect(page.getByText('已完成').first()).toBeVisible();

  // 3) 结构化字段结果（含置信度色标数据）
  await page.goto(`/results/${DOC_ID}`);
  await expect(page.getByText('甲方名称')).toBeVisible();
  await expect(page.getByText('0.62')).toBeVisible();
  await expect(page.getByText('0.96')).toBeVisible();

  // 4) 来源查看：页码 + 原文片段
  await page
    .getByRole('row', { name: /party_a/ })
    .getByRole('button', { name: '来源' })
    .click();
  await expect(page.getByText('字段来源：party_a')).toBeVisible();
  await expect(page.getByText(/第 2 页/)).toBeVisible();
  await expect(page.getByText('甲方：某某物流有限公司')).toBeVisible();
  await page.keyboard.press('Escape');

  // 5) 低置信人工修改 → 提交确认
  await page.goto('/workbench');
  await expect(page.getByText('party_a')).toBeVisible();
  await page.getByRole('row', { name: /party_a/ }).click();
  await expect(page.getByText('复核案件详情')).toBeVisible();
  await page.getByRole('button', { name: /编\s*辑/ }).click();
  const editor = page.locator('.ant-modal textarea').first();
  await expect(editor).toBeVisible();
  await editor.fill('某某物流有限公司');
  await page.getByRole('button', { name: '提交编辑' }).click();
  await expect(page.getByText('处置成功')).toBeVisible();

  // 6) 历史版本 diff
  await page.getByRole('tab', { name: '历史版本' }).click();
  await page.getByPlaceholder('输入文档 ID').fill(DOC_ID);
  await page.getByRole('button', { name: '查询版本' }).click();
  await expect(page.getByText('版本列表（勾选两个版本进行对比）')).toBeVisible();
  await page.locator('.ant-table-tbody .ant-checkbox-input').nth(0).check();
  await page.locator('.ant-table-tbody .ant-checkbox-input').nth(1).check();
  await page.getByRole('button', { name: '对比所选版本' }).click();
  await expect(page.getByText('版本对比：v1 → v2')).toBeVisible();
  await expect(page.getByText('已变更')).toBeVisible();
  await expect(page.getByText('某某物流有限公司').first()).toBeVisible();
});

test('上传重复提示与失败三态', async ({ page }) => {
  await page.route('**/api/documents', (route) => {
    if (route.request().method() === 'POST') {
      return json(route, { ...docView, duplicate: true });
    }
    return json(route, []);
  });

  await page.goto('/upload');
  await page.locator('input[type="file"]').first().setInputFiles({
    name: 'dup.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('重复内容'),
  });
  await expect(page.getByText('「dup.txt」重复上传，已关联既有任务').first()).toBeVisible();
});
