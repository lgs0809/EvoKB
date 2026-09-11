import { expect, test } from '@playwright/test';

test.beforeEach(async ({ page }) => { await page.goto('/e2e/task-failure.html'); });

test('persistent waiting explains the current attempt without claiming a dependency failure', async ({ page }) => {
  const notice = page.locator('#waiting');
  await expect(notice).toContainText('摘要生成等待自动重试');
  await expect(notice).toContainText('第 3 次尝试未完成');
  await expect(notice).toContainText('失败后会继续退避重试');
  await expect(notice).not.toContainText('依赖暂时不可用');
  await expect(notice.locator('pre')).not.toBeVisible();
  await notice.getByText('查看管理员诊断', { exact: true }).click();
  await expect(notice.locator('pre')).toBeVisible();
  await expect(notice.locator('pre')).toHaveText('grounding constraint audit: synthetic malformed candidate span');
  await expect(notice).toContainText('synthetic-task-1');
});

test('running never renders stale failure as a current failure', async ({ page }) => {
  await expect(page.locator('#running')).toBeEmpty();
});

test('a retained terminal failure never claims a five-attempt stop', async ({ page }) => {
  const notice = page.locator('#exhausted');
  await expect(notice).toContainText('摘要生成未完成');
  await expect(notice).not.toContainText('已达 5 次尝试');
  await expect(notice).toContainText('失败记录已保留');
  await expect(notice).not.toContainText('自动重试，');
  await expect(notice.getByText('查看管理员诊断', { exact: true })).toBeVisible();
});

test('a terminal attempt below the limit offers a manual retry rather than promising one', async ({ page }) => {
  await expect(page.locator('#retryable')).toContainText('可以重试当前阶段');
  await expect(page.locator('#retryable')).not.toContainText('系统将在');
});

test('the persisted deadline category has a Chinese explanation without guessing from diagnosis', async ({ page }) => {
  await expect(page.locator('#expired')).toContainText('本次处理达到阶段时限');
  await expect(page.locator('#expired')).not.toContainText('已达 5 次尝试');
  await expect(page.locator('#expired').locator('pre')).not.toBeVisible();
  await expect(page.locator('#exhausted')).not.toContainText('达到阶段时限');
});

test('a reader receives useful copy without raw administrator diagnosis', async ({ page }) => {
  const notice = page.locator('#reader');
  await expect(notice).toContainText('摘要生成等待自动重试');
  await expect(notice.locator('details')).toHaveCount(0);
  await expect(notice).not.toContainText('SUMMARY_GENERATION_FAILED');
  await expect(notice).not.toContainText('synthetic malformed candidate span');
});

test('unknown stages and ingestion share the same safe diagnostic presentation', async ({ page }) => {
  await expect(page.locator('#unknown')).toContainText('知识生成未完成');
  const original = page.locator('#ingestion');
  await expect(original).toContainText('原件校验未通过');
  await expect(original).toContainText('直接重新处理无法修复原件或校验信息');
  await expect(original.locator('pre')).not.toBeVisible();
  await original.getByText('查看管理员诊断', { exact: true }).click();
  await expect(original.locator('pre')).toHaveText('synthetic storage diagnosis');
});

test('generation and raw continue waiting after twelve attempts without a permanent-stop claim',async({page})=>{
  await expect(page.locator('#persistent')).toContainText('第 12 次尝试未完成');
  await expect(page.locator('#persistent')).toContainText('失败后会继续退避重试');
  await expect(page.locator('#ingestion-waiting')).toContainText('入库等待自动重试');
  await expect(page.locator('#ingestion-waiting')).toContainText('已执行 12 次');
  await expect(page.locator('#ingestion-waiting')).not.toContainText('已停止');
  await expect(page.locator('#ingestion-waiting').locator('details')).toHaveCount(0);
});
