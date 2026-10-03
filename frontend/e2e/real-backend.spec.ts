import { test, expect } from '@playwright/test';

/**
 * 真实后端冒烟 e2e（可选）：E2E_REAL_BACKEND=1 且本地 8090 已启动 wiki-Agent 后端时运行。
 * 链路：真实上传 txt → 任务出现在列表 → 详情步骤渲染。
 * 默认跳过（本地无 Docker/LLM key 时全链路产物依赖不可用，仅做冒烟级断言）。
 */
const REAL = process.env.E2E_REAL_BACKEND === '1';

test.describe('真实后端冒烟', () => {
  test.skip(!REAL, '设置 E2E_REAL_BACKEND=1 并启动本地后端后运行');

  test('真实上传 txt 并查看任务', async ({ page }) => {
    const filename = `e2e-smoke-${Date.now()}.txt`;
    await page.goto('/upload');
    await page.locator('input[type="file"]').first().setInputFiles({
      name: filename,
      mimeType: 'text/plain',
      buffer: Buffer.from('e2e 冒烟测试内容', 'utf-8'),
    });
    // 上传成功或幂等重复均视为链路可用
    await expect(
      page.getByText(`「${filename}」上传成功`).or(page.getByText('重复上传，已关联既有任务')),
    ).toBeVisible({ timeout: 15_000 });

    await page.goto('/tasks?mine=true');
    await expect(page.getByText('INGEST').first()).toBeVisible({ timeout: 15_000 });
  });
});
