import { expect, test } from '@playwright/test';

test('fresh deployment supports the core product journey on desktop and narrow screens', async ({ page }, testInfo) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  const httpErrors: string[] = [];
  const anonymousProbe = (url: string) => new URL(url).pathname === '/api/enterprise/auth/me';

  page.on('console', message => {
    // Chromium reports the expected anonymous session probe as a resource error.
    if (message.type() === 'error' && !(message.text().includes('401') && anonymousProbe(message.location().url || page.url()))) {
      consoleErrors.push(message.text());
    }
  });
  page.on('pageerror', error => pageErrors.push(error.message));
  page.on('response', response => {
    const status = response.status();
    if (status >= 400 && !(status === 401 && anonymousProbe(response.url()))) {
      httpErrors.push(`${status} ${new URL(response.url()).pathname}`);
    }
  });

  const generatedSecret = crypto.randomUUID();
  const username = 'release-smoke-admin';
  const expectPage = async (title: string, path: string) => {
    await expect(page).toHaveURL(url => url.pathname === path);
    await expect(page.getByRole('heading', { name: title, level: 1, exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1);
    await expect(page.locator('main')).toHaveCount(1);
  };
  const contentHeadings: Record<string, string> = {
    '/enterprise': '需要关注', '/enterprise/ask': '想了解什么？', '/enterprise/history': '查询记录',
    '/enterprise/documents': '资料', '/enterprise/wiki': '企业百科', '/enterprise/graph': '关系查询',
    '/enterprise/ingestion': '入库记录', '/enterprise/tasks': '知识生成', '/enterprise/quality': '知识质量',
    '/enterprise/search': '检索测试', '/enterprise/models': '模型配置', '/enterprise/audit': '审计日志',
    '/enterprise/admin': '用户与权限',
  };
  const expectWorkspace = async (title: string, path: string) => {
    await expectPage(title, path);
    await expect(page.locator('main').getByRole('heading', { name: contentHeadings[path], level: 2, exact: true })).toBeVisible();
  };

  await test.step('bootstrap the first administrator through the real form', async () => {
    const before = await page.request.get('/api/enterprise/auth/status');
    expect(before.status()).toBe(200);
    expect(await before.json()).toMatchObject({ setup_required: true, users: 0 });
    await page.goto('/enterprise');
    await expectPage('初始化 EvoKB 管理员', '/enterprise');
    const secret = page.getByPlaceholder('输入密码', { exact: true });
    await expect(secret).toHaveAttribute('type', 'password');
    await expect(secret).toHaveAttribute('minlength', '10');
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByLabel('显示名称', { exact: true }).fill('发布验收管理员');
    await secret.fill(generatedSecret);
    await page.getByPlaceholder('再次输入密码', { exact: true }).fill(generatedSecret);
    const created = page.waitForResponse(response => response.url().endsWith('/api/enterprise/auth/setup') && response.request().method() === 'POST');
    await page.getByRole('button', { name: '创建管理员并进入', exact: true }).click();
    const response = await created;
    expect(response.status()).toBe(201);
    expect((await response.json()).user).toMatchObject({ username, role: 'ADMIN' });
    await expectWorkspace('知识概览', '/enterprise');
    await expect(page.getByText('EvoKB', { exact: true })).toBeVisible();
    const after = await page.request.get('/api/enterprise/auth/status');
    expect(after.status()).toBe(200);
    expect(await after.json()).toMatchObject({ setup_required: false, users: 1 });
    await testInfo.attach('desktop-overview', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
  });

  // Freeze the public navigation contract independently of the UI registry.
  const desktopSections = [
    ['知识查询', '/enterprise/ask'], ['查询记录', '/enterprise/history'], ['知识概览', '/enterprise'],
    ['资料库', '/enterprise/documents'], ['知识条目', '/enterprise/wiki'], ['关系图谱', '/enterprise/graph'],
    ['上传与处理', '/enterprise/ingestion'],
  ];
  const managementSections = [
    ['知识生成', '/enterprise/tasks'], ['质量检查', '/enterprise/quality'], ['检索诊断', '/enterprise/search'],
    ['模型服务', '/enterprise/models'], ['操作审计', '/enterprise/audit'], ['用户与权限', '/enterprise/admin'],
  ];
  const navigation = page.getByRole('navigation', { name: '页面导航' });
  const visit = async ([title, path]: string[]) => {
    const link = navigation.getByRole('link', { name: title, exact: true });
    await expect(link).toHaveAttribute('href', path);
    await link.click();
    await expectWorkspace(title, path);
    await expect(page.locator('nav[aria-label="页面导航"] a[aria-current="page"]')).toHaveAttribute('href', path);
  };
  for (const section of desktopSections) await test.step(`desktop: ${section[0]}`, () => visit(section));
  await navigation.getByRole('button', { name: '管理与维护', exact: true }).click();
  for (const section of managementSections) await test.step(`management: ${section[0]}`, () => visit(section));

  await test.step('open and dismiss the real upload dialog without starting ingestion', async () => {
    await visit(['资料库', '/enterprise/documents']);
    await page.getByRole('button', { name: '添加资料', exact: true }).click();
    await expectWorkspace('上传与处理', '/enterprise/ingestion');
    await page.getByRole('button', { name: '上传资料', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '批量添加资料', exact: true });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('heading', { name: '批量添加资料', exact: true })).toBeVisible();
    await expect(dialog.getByText('拖拽文件或 ZIP 到这里', { exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expectWorkspace('上传与处理', '/enterprise/ingestion');
  });

  await test.step('navigate through the narrow-screen drawer', async () => {
    await page.setViewportSize({ width: 390, height: 844 });
    await page.getByRole('button', { name: '打开导航', exact: true }).click();
    await expect(page.getByRole('dialog', { name: '主导航', exact: true })).toBeVisible();
    await expect(navigation.getByRole('link', { name: '知识查询', exact: true })).toBeVisible();
    await visit(['知识查询', '/enterprise/ask']);
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.getByRole('button', { name: '打开导航', exact: true })).toHaveAttribute('aria-expanded', 'false');
    await testInfo.attach('narrow-query', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
  });

  await test.step('end the session and sign in again with the same real account', async () => {
    await page.getByRole('button', { name: '账户菜单', exact: true }).click();
    const ended = page.waitForResponse(response => response.url().endsWith('/api/enterprise/auth/logout') && response.request().method() === 'POST');
    await page.getByRole('button', { name: '退出登录', exact: true }).click();
    expect((await ended).status()).toBe(200);
    await expectPage('登录 EvoKB', '/enterprise/ask');
    expect((await page.request.get('/api/enterprise/auth/me')).status()).toBe(401);
    await page.getByLabel('用户名', { exact: true }).fill(username);
    await page.getByPlaceholder('输入密码', { exact: true }).fill(generatedSecret);
    const signedIn = page.waitForResponse(response => response.url().endsWith('/api/enterprise/auth/login') && response.request().method() === 'POST');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    const response = await signedIn;
    expect(response.status()).toBe(200);
    expect((await response.json()).user).toMatchObject({ username, role: 'ADMIN' });
    await expectWorkspace('知识查询', '/enterprise/ask');
    const me = await page.request.get('/api/enterprise/auth/me');
    expect(me.status()).toBe(200);
    expect((await me.json()).user).toMatchObject({ username, role: 'ADMIN' });
  });

  expect(consoleErrors, `console.error: ${consoleErrors.join(' | ')}`).toEqual([]);
  expect(pageErrors, `pageerror: ${pageErrors.join(' | ')}`).toEqual([]);
  expect(httpErrors, `unexpected HTTP errors: ${httpErrors.join(' | ')}`).toEqual([]);
});
