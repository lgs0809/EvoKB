import { expect, test } from '@playwright/test';

test('fresh deployment supports the core product journey on desktop and narrow screens', async ({ page }) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const httpErrors: string[] = [];

  page.on('console', message => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('response', response => {
    const status = response.status();
    const url = response.url();
    const expectedAnonymousProbe = status === 401 && url.includes('/api/enterprise/auth/me');
    if (status >= 400 && !expectedAnonymousProbe) httpErrors.push(`${status} ${new URL(url).pathname}`);
  });

  await page.goto('/enterprise');
  await expect(page.getByRole('heading', { name: '初始化管理员账号' })).toBeVisible();
  // The anonymous /auth/me probe intentionally returns 401 before the first account exists.
  consoleErrors.length = 0;

  const generatedSecret = crypto.randomUUID();
  await page.getByLabel('用户名').fill('release-smoke-admin');
  await page.getByLabel('显示名称').fill('发布验收管理员');
  await page.getByPlaceholder('输入密码', { exact: true }).fill(generatedSecret);
  await page.getByPlaceholder('再次输入密码', { exact: true }).fill(generatedSecret);
  await page.getByRole('button', { name: '创建管理员并进入' }).click();

  await expect(page.getByRole('heading', { name: '首页', level: 1 })).toBeVisible();
  await expect(page.getByText('企业知识平台', { exact: true })).toBeVisible();

  const desktopSections = ['知识查询', '企业百科', '关系图谱', '资料管理', '入库记录', '知识生成', '检索测试', '审计日志', '用户与系统'];
  for (const section of desktopSections) {
    await page.locator('nav').getByRole('button', { name: section, exact: true }).click();
    await expect(page.getByRole('heading', { name: section, level: 1 })).toBeVisible();
  }

  await page.locator('nav').getByRole('button', { name: '资料管理', exact: true }).click();
  await page.getByRole('button', { name: '添加资料' }).click();
  await expect(page.getByRole('heading', { name: '批量添加资料' })).toBeVisible();
  await expect(page.getByText('拖拽文件或 ZIP 到这里')).toBeVisible();
  await page.getByRole('button', { name: '稍后查看' }).click();
  await expect(page.getByRole('heading', { name: '资料管理', level: 1 })).toBeVisible();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: '打开导航' }).click();
  await expect(page.locator('nav').getByRole('button', { name: '知识查询', exact: true })).toBeVisible();
  await page.locator('nav').getByRole('button', { name: '知识查询', exact: true }).click();
  await expect(page.getByRole('heading', { name: '知识查询', level: 1 })).toBeVisible();

  expect(consoleErrors, `console.error: ${consoleErrors.join(' | ')}`).toEqual([]);
  expect(pageErrors, `pageerror: ${pageErrors.join(' | ')}`).toEqual([]);
  expect(httpErrors, `unexpected HTTP errors: ${httpErrors.join(' | ')}`).toEqual([]);
});
