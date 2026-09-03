const fs = require('fs');
const { chromium } = require('@playwright/test');

function tokenFrom(file) {
  const text = fs.readFileSync(file, 'utf8');
  const line = text.split(/\r?\n/).find(v => v.includes('\tekb_session\t'));
  if (!line) throw new Error(`session cookie missing: ${file}`);
  return line.split('\t').pop();
}

async function open(page, base, path) {
  await page.goto(`${base}${path}?ts=${Date.now()}`, { waitUntil: 'networkidle' });
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

    await open(page, base, '/enterprise/models');
    await page.getByRole('heading', { name: '模型配置', exact: true }).first().waitFor();
    await page.getByRole('button', { name: '新建配置组', exact: true }).click();
    await page.getByRole('heading', { name: '新建模型配置组', exact: true }).waitFor();
    await page.getByRole('button', { name: '取消', exact: true }).click();

    await open(page, base, '/enterprise/history');
    const main = page.getByRole('main');
    const refresh = main.getByRole('button', { name: '刷新', exact: true });
    await refresh.waitFor();
    const refreshBox = await refresh.boundingBox();
    const checks = main.locator('input[type="checkbox"]');
    let bulkBox = null;
    if (await checks.count() >= 2) {
      await checks.nth(0).check();
      await checks.nth(1).check();
      const bulk = main.getByRole('button', { name: /将 2 条设为一组/ });
      await bulk.waitFor();
      bulkBox = await bulk.boundingBox();
    }

    await open(page, base, '/enterprise/ask');
    const scope = page.locator('details').filter({ hasText: '知识范围' }).first();
    await scope.waitFor();
    await scope.locator('summary').click();
    const selects = scope.locator('select');
    await selects.first().waitFor();
    const boxes = [];
    for (let i = 0; i < Math.min(3, await selects.count()); i++) boxes.push(await selects.nth(i).boundingBox());
    const sameRow = boxes.length === 3 && Math.max(...boxes.map(b => b.y)) - Math.min(...boxes.map(b => b.y)) < 4;
    const appearance = await selects.first().evaluate(el => getComputedStyle(el).appearance);
    const rightPadding = await selects.first().evaluate(el => getComputedStyle(el).paddingRight);
    const scopeText = await scope.textContent();

    await open(page, base, '/enterprise/documents');
    await page.getByRole('heading', { name: '资料', exact: true }).first().waitFor();
    const docsText = await main.textContent();

    await open(page, base, '/enterprise');
    const nav = page.locator('aside nav');
    const labels = ['知识查询','查询记录','资料管理','企业百科','关系图谱','入库记录'];
    const iconShapes = {};
    for (const label of labels) {
      const button = nav.getByRole('button', { name: label, exact: true });
      if (await button.count()) iconShapes[label] = await button.locator('svg').innerHTML();
    }
    await nav.getByRole('button', { name: '管理', exact: true }).click();
    for (const label of ['知识生成','知识质量','检索测试','模型配置','审计日志','用户与系统']) {
      const button = nav.getByRole('button', { name: label, exact: true });
      iconShapes[label] = await button.locator('svg').innerHTML();
    }
    const uniqueIcons = new Set(Object.values(iconShapes)).size;

    console.log(JSON.stringify({
      nativeDialog,
      siteCreateModal: true,
      refreshAndBulkSeparated: !bulkBox || Math.abs(refreshBox.y - bulkBox.y) > 35,
      knowledgeScopeSameRow: sameRow,
      knowledgeScopeChineseOk: scopeText.includes('知识范围') && scopeText.includes('来源部门') && scopeText.includes('文档类型') && scopeText.includes('材料分类'),
      nativeSelectArrowRemoved: appearance === 'none',
      selectRightPadding: rightPadding,
      companyAbsentFromKnowledgeScope: await page.getByText('公司', { exact: true }).count() === 0,
      companyAbsentFromDocuments: !docsText.includes('公司 / 部门') && !docsText.includes('公司\n'),
      navIconCount: Object.keys(iconShapes).length,
      navUniqueIconCount: uniqueIcons,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
