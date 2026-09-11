import { expect, test, type Page } from '@playwright/test';

const source = {
  document_id: 'synthetic-document', logical_name: '合成验收资料', version_id: 'synthetic-original-active',
  version_no: 1, lifecycle: 'ACTIVE', original_filename: 'synthetic-source.pdf', security_level: 30,
  summary_count: 0, grounded_summaries: 0, covered_summaries: 0, effective_hits: 0,
};

async function prepare(page: Page, reader = false, documents = [source]) {
  await page.route('**/api/enterprise/documents?**', route => route.fulfill({ json: {
    documents, total: documents.length, page: 1, page_size: 30,
  } }));
  await page.route('**/api/enterprise/facets', route => route.fulfill({ json: {
    companies: [], source_departments: [], document_types: [], material_categories: [],
  } }));
  await page.route('**/api/enterprise/document/detail?**', route => {
    const id = new URL(route.request().url()).searchParams.get('version_id');
    const item = documents.find(item => item.version_id === id) || source;
    return route.fulfill({ json: {
      document_id: item.document_id, version_id: item.version_id, blocks: [], knowledge: {},
    } });
  });
  await page.goto(`/e2e/document-reparse.html${reader ? '?reader=1' : ''}`);
  const selected = documents[0] || source;
  await page.getByRole('button', { name: selected.logical_name, exact: true }).click();
  await expect(page.getByRole('complementary', { name: '资料详情' })).toBeVisible();
  await expect(page.getByRole('complementary', { name: '资料详情' })).toContainText(selected.version_id);
}

async function settlePaint(page: Page) {
  await page.evaluate(() => new Promise<void>(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
  }));
}

test.describe('document parsing calendar day', () => {
  test.use({ timezoneId: 'Asia/Shanghai' });
  test('UTC parsing timestamp crosses midnight into the browser local calendar day', async ({ page }) => {
    const parsed = { ...source, parsed_at: '2026-11-02T16:35:04.373134Z' };
    await prepare(page, false, [parsed]);
    const row = page.getByRole('row', { name: new RegExp(source.logical_name) });
    await expect(row.getByText('2026-11-03', { exact: true })).toBeVisible();
    await expect(row.getByText('2026-11-02', { exact: true })).toHaveCount(0);
  });
});

test.describe('explicit business date', () => {
  test.use({ timezoneId: 'America/Los_Angeles' });
  test('date-only business date stays literal and takes precedence over parsing time', async ({ page }) => {
    const dated = { ...source, document_date: '2026-11-03', parsed_at: '2026-11-02T16:35:04.373134Z' };
    await prepare(page, false, [dated]);
    const row = page.getByRole('row', { name: new RegExp(source.logical_name) });
    await expect(row.getByText('2026-11-03', { exact: true })).toBeVisible();
    await expect(row.getByText('2026-11-02', { exact: true })).toHaveCount(0);
  });
});

test('the native confirmation can be cancelled without starting a task', async ({ page }) => {
  let calls = 0;
  await page.route('**/api/enterprise/document/reparse?**', () => { calls++; });
  await prepare(page);
  await page.getByRole('button', { name: '重新解析', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '重新解析资料' });
  await expect(dialog).toContainText('现有可用版本继续提供查询');
  await expect(dialog).toContainText('相同原文与规则会复用原任务');
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  expect(calls).toBe(0);
  await expect(page.getByRole('heading', { name: source.logical_name })).toBeVisible();
});

test('one pending submission retains the old version and cannot be dismissed as completed', async ({ page }) => {
  let finish: (() => Promise<void>) | undefined;
  let calls = 0;
  await page.route('**/api/enterprise/document/reparse?**', route => {
    calls++;
    expect(route.request().method()).toBe('POST');
    expect(new URL(route.request().url()).searchParams.get('version_id')).toBe(source.version_id);
    finish = () => route.fulfill({ json: { batch_id: 'synthetic-new-batch', file_id: 'synthetic-new-file', version_id: 'synthetic-new-pending', reused: false } });
  });
  await prepare(page);
  await page.getByRole('button', { name: '重新解析', exact: true }).click();
  await page.getByRole('button', { name: '开始重新解析' }).click();
  await expect.poll(() => calls).toBe(1);
  await expect(page.getByRole('button', { name: '正在提交…' })).toBeDisabled();
  await page.keyboard.press('Escape');
  await expect(page.getByRole('dialog', { name: '重新解析资料' })).toBeVisible();
  expect(finish).toBeDefined();
  await finish!();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  await expect(page.getByRole('status', { name: '重新解析状态' })).toContainText('重新解析已排队');
  await expect(page.getByRole('status', { name: '重新解析状态' })).toContainText('新版本处理成功后才会替换');
  await expect(page.getByRole('heading', { name: source.logical_name })).toBeVisible();
  expect(calls).toBe(1);
  await page.getByRole('button', { name: '查看处理记录' }).click();
  await expect(page.getByLabel('导航目的地')).toHaveText('ingestion');
});

test('same-identity reuse explains retained attempts instead of announcing a new task', async ({ page }) => {
  await page.route('**/api/enterprise/document/reparse?**', route => route.fulfill({ json: {
    batch_id: 'synthetic-old-batch', file_id: 'synthetic-failed-five', version_id: source.version_id, reused: true,
  } }));
  await prepare(page);
  await page.getByRole('button', { name: '重新解析', exact: true }).click();
  await page.getByRole('button', { name: '开始重新解析' }).click();
  await expect(page.getByRole('status', { name: '重新解析状态' })).toContainText('已复用原任务');
  await expect(page.getByRole('status', { name: '重新解析状态' })).toContainText('尝试次数不会重置');
  await expect(page.getByRole('status', { name: '重新解析状态' })).not.toContainText('已排队');
});

test('a current permission rejection stays visible inside the dialog and never claims success', async ({ page }) => {
  await page.route('**/api/enterprise/document/reparse?**', route => route.fulfill({ status: 403, json: {
    error: '仅资料所有者或管理员可以重新解析',
  } }));
  await prepare(page);
  await page.getByRole('button', { name: '重新解析', exact: true }).click();
  await page.getByRole('button', { name: '开始重新解析' }).click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('仅资料所有者或管理员');
  await expect(page.getByRole('button', { name: '开始重新解析' })).toBeEnabled();
  await expect(page.getByRole('status', { name: '重新解析状态' })).toHaveCount(0);
  await expect(page.getByRole('heading', { name: source.logical_name })).toBeVisible();
});

test('read-only capabilities do not offer a write action', async ({ page }) => {
  await prepare(page, true);
  await expect(page.getByRole('button', { name: '重新解析', exact: true })).toHaveCount(0);
});

test('refresh follows the newly active version without requesting the retired detail', async ({ page }) => {
  await prepare(page);
  const published = { ...source, version_id: 'synthetic-current-published', version_no: 2 };
  await page.route('**/api/enterprise/documents?**', route => route.fulfill({ json: {
    documents: [published], total: 1, page: 1, page_size: 30,
  } }));
  let retiredRequests = 0;
  await page.route('**/api/enterprise/document/detail?**', route => {
    const id = new URL(route.request().url()).searchParams.get('version_id');
    if (id === source.version_id) {
      retiredRequests++;
      return route.fulfill({ status: 403, json: { error: '历史版本已经失效' } });
    }
    return route.fulfill({ json: {
      document_id: source.document_id, version_id: published.version_id, blocks: [], knowledge: {},
    } });
  });
  await page.getByRole('button', { name: '刷新当前页面', exact: true }).click();
  const detail = page.getByRole('complementary', { name: '资料详情' });
  await expect(detail).toContainText(published.version_id);
  await expect(detail).not.toContainText(source.version_id);
  await expect(page.getByLabel('页面提示')).toHaveCount(0);
  expect(retiredRequests).toBe(0);
});

test('refresh clears an unavailable selection instead of keeping stale source details', async ({ page }) => {
  await prepare(page);
  await page.route('**/api/enterprise/documents?**', route => route.fulfill({ json: {
    documents: [], total: 0, page: 1, page_size: 30,
  } }));
  let unavailableRequests = 0;
  await page.route('**/api/enterprise/document/detail?**', route => {
    unavailableRequests++;
    return route.fulfill({ status: 403, json: { error: '当前资料已不可访问' } });
  });
  await page.getByRole('button', { name: '刷新当前页面', exact: true }).click();
  await expect(page.getByRole('complementary', { name: '资料详情' })).toHaveCount(0);
  await expect(page.getByText('暂无资料', { exact: true })).toBeVisible();
  await expect(page.getByLabel('页面提示')).toHaveCount(0);
  expect(unavailableRequests).toBe(0);
});

test('a delayed refresh does not reopen details closed while the list was loading', async ({ page }) => {
  await prepare(page);
  const fresh = { ...source, logical_name: '刷新完成的合成资料' };
  let release: (() => Promise<void>) | undefined;
  await page.route('**/api/enterprise/documents?**', route => {
    release = () => route.fulfill({ json: { documents: [fresh], total: 1, page: 1, page_size: 30 } });
  });
  await page.getByRole('button', { name: '刷新当前页面', exact: true }).click();
  await expect.poll(() => Boolean(release)).toBe(true);
  await page.getByRole('button', { name: '关闭详情', exact: true }).click();
  await expect(page.getByRole('complementary', { name: '资料详情' })).toHaveCount(0);
  await release!();
  await expect(page.getByRole('button', { name: fresh.logical_name, exact: true })).toBeVisible();
  await settlePaint(page);
  await expect(page.getByRole('complementary', { name: '资料详情' })).toHaveCount(0);
});

test('a delayed refresh does not take the selection back from another document', async ({ page }) => {
  const other = { ...source, document_id: 'synthetic-other-document', version_id: 'synthetic-other-version', logical_name: '另一份合成资料' };
  await prepare(page, false, [source, other]);
  const fresh = { ...source, logical_name: '刷新完成的合成资料' };
  let release: (() => Promise<void>) | undefined;
  await page.route('**/api/enterprise/documents?**', route => {
    if (new URL(route.request().url()).searchParams.has('document_id')) {
      return route.fulfill({ json: { documents: [other], total: 1, page: 1, page_size: 30 } });
    }
    release = () => route.fulfill({ json: { documents: [fresh, other], total: 2, page: 1, page_size: 30 } });
  });
  await page.getByRole('button', { name: '刷新当前页面', exact: true }).click();
  await expect.poll(() => Boolean(release)).toBe(true);
  await page.getByRole('button', { name: other.logical_name, exact: true }).click();
  await expect(page.getByRole('complementary', { name: '资料详情' })).toContainText(other.version_id);
  await release!();
  await expect(page.getByRole('button', { name: fresh.logical_name, exact: true })).toBeVisible();
  await settlePaint(page);
  await expect(page.getByRole('complementary', { name: '资料详情' })).toContainText(other.version_id);
  await expect(page.getByRole('complementary', { name: '资料详情' })).not.toContainText(source.version_id);
});

test('a delayed previous filter response cannot replace the current results', async ({ page }) => {
  await prepare(page);
  const current = { ...source, document_id: 'synthetic-current-filter', version_id: 'synthetic-current-filter-version', logical_name: '当前筛选资料' };
  let release: (() => Promise<void>) | undefined;
  await page.route('**/api/enterprise/documents?**', route => {
    const query = new URL(route.request().url()).searchParams.get('q');
    if (query === '旧筛选') {
      release = () => route.fulfill({ json: { documents: [{ ...source, logical_name: '迟到的旧筛选资料' }], total: 1, page: 1, page_size: 30 } });
      return;
    }
    return route.fulfill({ json: { documents: [current], total: 1, page: 1, page_size: 30 } });
  });
  await page.getByPlaceholder('搜索资料名称或文件名').fill('旧筛选');
  await expect.poll(() => Boolean(release)).toBe(true);
  await page.getByPlaceholder('搜索资料名称或文件名').fill('当前筛选');
  await expect(page.getByRole('button', { name: current.logical_name, exact: true })).toBeVisible();
  const response = page.waitForResponse(result => new URL(result.url()).searchParams.get('q') === '旧筛选');
  await release!();
  await (await response).finished();
  await settlePaint(page);
  await expect(page.getByRole('button', { name: current.logical_name, exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '迟到的旧筛选资料', exact: true })).toHaveCount(0);
});

test('an error from a closed detail request does not pollute the current page', async ({ page }) => {
  await prepare(page);
  await page.getByRole('button', { name: '关闭详情', exact: true }).click();
  let release: (() => Promise<void>) | undefined;
  await page.route('**/api/enterprise/document/detail?**', route => {
    release = () => route.fulfill({ status: 403, json: { error: '迟到的详情错误' } });
  });
  await page.getByRole('button', { name: source.logical_name, exact: true }).click();
  await expect.poll(() => Boolean(release)).toBe(true);
  await page.getByRole('button', { name: '关闭详情', exact: true }).click();
  const response = page.waitForResponse(result => result.url().includes('/document/detail?'));
  await release!();
  await (await response).finished();
  await settlePaint(page);
  await expect(page.getByRole('complementary', { name: '资料详情' })).toHaveCount(0);
  await expect(page.getByLabel('页面提示')).toHaveCount(0);
});

for (const width of [390, 768, 1280]) {
  test(`long document names remain readable and recovery actions reachable at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 844 });
    const logical_name = '跨部门交付验收_' + 'LongUnbrokenDocumentIdentifier'.repeat(12);
    await prepare(page, false, [{ ...source, logical_name, original_filename: logical_name + '.pdf' }]);
    const heading = page.getByRole('heading', { name: logical_name, exact: true });
    await expect(heading).toHaveText(logical_name);
    expect(await heading.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    const widths = await page.evaluate(() => ({ page: document.documentElement.scrollWidth, viewport: innerWidth }));
    expect(widths.page).toBeLessThanOrEqual(widths.viewport);
    await page.getByRole('button', { name: '重新解析', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '重新解析资料' });
    await expect(dialog).toContainText(logical_name);
    const surface = dialog.locator(':scope > div');
    expect(await surface.evaluate(element => getComputedStyle(element).backgroundColor)).not.toBe('rgba(0, 0, 0, 0)');
    expect(await surface.evaluate(element => element.getBoundingClientRect().width)).toBeLessThanOrEqual(Math.min(512, width - 32) + 1);
    expect(await dialog.evaluate(element => element.scrollWidth <= element.clientWidth + 1)).toBe(true);
    await expect(page.getByRole('button', { name: '开始重新解析', exact: true })).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
  });
}
