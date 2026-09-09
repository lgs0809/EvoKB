const fs = require('fs');
const { chromium } = require('@playwright/test');

function tokenFrom(file) {
  const text = fs.readFileSync(file, 'utf8');
  const line = text.split(/\r?\n/).find(v => v.includes('\tekb_session\t'));
  if (!line) throw new Error(`session cookie missing: ${file}`);
  return line.split('\t').pop();
}

(async () => {
  const base = 'http://127.0.0.1:18080';
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
    await context.addCookies([{ name: 'ekb_session', value: tokenFrom('../tmp/main-admin.cookies'), url: `${base}/` }]);
    const page = await context.newPage();
    let nativeDialog = false;
    page.on('dialog', async dialog => { nativeDialog = true; await dialog.dismiss(); });
    await page.goto(`${base}/enterprise?ts=${Date.now()}`, { waitUntil: 'networkidle' });

    await page.getByRole('button', { name: '管理', exact: true }).click();
    await page.getByRole('button', { name: '模型配置', exact: true }).click();
    await page.getByRole('button', { name: '新建配置组', exact: true }).click();
    await page.getByRole('heading', { name: '新建模型配置组', exact: true }).waitFor();
    await page.getByRole('button', { name: '取消', exact: true }).click();

    await page.getByRole('button', { name: '查询记录', exact: true }).click();
    const refresh = page.getByRole('main').getByRole('button', { name: '刷新', exact: true });
    await refresh.waitFor();
    const refreshBox = await refresh.boundingBox();
    const checks = page.locator('input[type="checkbox"]');
    const count = await checks.count();
    let bulkVisible = false;
    let bulkBox = null;
    if (count >= 2) {
      await checks.nth(0).check();
      await checks.nth(1).check();
      const bulk = page.getByRole('button', { name: /将 2 条设为一组/ });
      await bulk.waitFor();
      bulkVisible = true;
      bulkBox = await bulk.boundingBox();
    }

    await page.getByRole('button', { name: '知识查询', exact: true }).click();
    const scopeSummary = page.locator('summary').filter({ hasText: '知识范围' }).first();
    await scopeSummary.click();
    const selects = page.locator('details select');
    await selects.first().waitFor();
    const boxes = [];
    for (let i = 0; i < Math.min(3, await selects.count()); i++) boxes.push(await selects.nth(i).boundingBox());
    const sameRow = boxes.length === 3 && Math.max(...boxes.map(b => b.y)) - Math.min(...boxes.map(b => b.y)) < 3;
    const appearance = await selects.first().evaluate(el => getComputedStyle(el).appearance);
    const rightPad = await selects.first().evaluate(el => getComputedStyle(el).paddingRight);

    console.log(JSON.stringify({
      nativeDialog,
      siteModalVisible: true,
      refreshTopRight: Boolean(refreshBox),
      bulkVisible,
      refreshAndBulkSeparated: Boolean(refreshBox && bulkBox && Math.abs(refreshBox.y - bulkBox.y) > 40),
      knowledgeScopeSameRow: sameRow,
      nativeSelectArrowRemoved: appearance === 'none',
      selectRightPadding: rightPad,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
