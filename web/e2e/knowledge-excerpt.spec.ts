import { test, expect } from '@playwright/test';

test('source excerpts preserve cell boundaries and business text without displaying HTML or executing it', async ({ page }) => {
  const unexpectedRequests: string[] = [];
  page.on('request', request => {
    if (request.url().includes('renderer-test-missing')) unexpectedRequests.push(request.url());
  });
  await page.goto('/e2e/markdown-renderer.html');
  const excerpt = page.locator('#excerpt-content');
  await expect(excerpt).toContainText('采购明细 合同 HT-ENTRY-104 900 万元 尾款条件完整保留');
  await expect(excerpt).toContainText('验收后 15 日 & 合法发票。');
  await expect(excerpt).not.toContainText('<td');
  await expect(excerpt).not.toContainText('document.body');
  await expect(excerpt.locator('table,a,img,script,form,button')).toHaveCount(0);
  expect(await page.locator('body').getAttribute('data-executed')).toBeNull();
  expect(unexpectedRequests).toEqual([]);
});
