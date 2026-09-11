import { test, expect, type Page } from '@playwright/test';

const file = { file_id: 'synthetic-old-file', batch_id: 'synthetic-old-batch', relative_path: 'old.md', original_filename: 'old.md',
  logical_name: '合成旧失败资料', size_bytes: 445, sha256: 'synthetic-only', status: 'FAILED', progress_stage: 'FAILED',
  attempt: 6, document_id: 'synthetic-document', version_id: 'synthetic-old-version', error_code: 'PARSER_IDENTITY_CHANGED',
  error_message: 'Synthetic retained unknown pipeline, not a model replacement claim', created_at: '2026-10-03T11:00:00Z', updated_at: '2026-11-02T15:00:00Z' };
const batch = { batch_id: file.batch_id, created_by_username: 'synthetic-admin', status: 'FAILED', source_count: 1,
  file_count: 1, staged_count: 0, queued_count: 0, running_count: 0, succeeded_count: 0, failed_count: 1,
  created_at: file.created_at, updated_at: file.updated_at, files: [file] };

async function prepare(page: Page, reader = false, withVersion = true) {
  const current = { ...batch, files: [{ ...file, version_id: withVersion ? file.version_id : undefined }] };
  await page.route('**/api/enterprise/batches?**', route => route.fulfill({ json: { batches: [current], total: 1, page: 1, page_size: 20 } }));
  await page.route('**/api/enterprise/batch?**', route => route.fulfill({ json: current }));
  await page.goto(`/e2e/ingestion-reparse.html${reader ? '?reader=1' : ''}`);
  await page.getByRole('button', { name: /查看详情/ }).click();
  await expect(page.getByText(file.logical_name, { exact: true })).toBeVisible();
}

test('retained FAILED6 offers current-pipeline reparse instead of retrying an unverifiable old identity', async ({ page }) => {
  let calls = 0;
  await page.route('**/api/enterprise/document/reparse?**', route => {
    calls++; expect(route.request().method()).toBe('POST');
    expect(new URL(route.request().url()).searchParams.get('version_id')).toBe(file.version_id);
    return route.fulfill({ json: { batch_id: 'synthetic-existing-batch', file_id: 'synthetic-existing-file', version_id: 'synthetic-current-version', reused: true } });
  });
  await prepare(page);
  await expect(page.getByText(/原任务解析身份缺失或不匹配/)).toBeVisible();
  await expect(page.getByText('解析规则已更新', { exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '重新处理', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '以当前配置重新解析' }).click();
  await expect(page.getByRole('dialog', { name: '重新解析资料' })).toContainText('历史版本与失败记录都会保留');
  await page.getByRole('button', { name: '开始重新解析' }).click();
  await expect(page.getByRole('status', { name: '重新解析状态' })).toContainText('已复用原任务');
  await expect(page.getByRole('status', { name: '重新解析状态' })).toContainText('尝试次数不会重置');
  await expect(page.getByText('资料版本已保留 · 第 6 次处理')).toBeVisible();
  expect(calls).toBe(1);
});

test('a current permission rejection remains an error without a claimed reparse success', async ({ page }) => {
  await page.route('**/api/enterprise/document/reparse?**', route => route.fulfill({ status: 403, json: { error: '仅资料所有者或管理员可以重新解析' } }));
  await prepare(page);
  await page.getByRole('button', { name: '以当前配置重新解析' }).click();
  await page.getByRole('button', { name: '开始重新解析' }).click();
  await expect(page.getByRole('dialog').getByRole('alert')).toContainText('仅资料所有者或管理员');
  await expect(page.getByRole('status', { name: '重新解析状态' })).toHaveCount(0);
});

test('ordinary members do not get the administrator recovery action', async ({ page }) => {
  await prepare(page, true);
  await expect(page.getByRole('button', { name: '以当前配置重新解析' })).toHaveCount(0);
});

test('a failed item without a reserved source version cannot invoke document reparse', async ({ page }) => {
  await prepare(page, false, false);
  await expect(page.getByRole('button', { name: '以当前配置重新解析' })).toHaveCount(0);
});
