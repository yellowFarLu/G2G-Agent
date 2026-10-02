import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright 配置（AC-G1 全链路 e2e）。
 * 默认 mock 模式：spec 内 page.route 拦截 /api/**，无需后端即可在 CI 运行。
 * 真实后端冒烟：E2E_REAL_BACKEND=1 且本地 8080 已启动后端时，运行 e2e/real-backend.spec.ts。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'retain-on-failure',
    locale: 'zh-CN',
  },
  // 本机网络无法下载官方 Chromium 时用系统 Chrome；CI（PW_CHROMIUM=1）用下载的 chromium
  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        channel: process.env.PW_CHROMIUM === '1' ? undefined : 'chrome',
      },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
