const fs = require('fs');
const { chromium } = require('@playwright/test');

(async () => {
  const base = 'http://127.0.0.1:18080';
  const groupId = '56a77db8-1cae-49b3-9f1e-5af1272456a1';
  const recordId = '9c4d8bc4-9e3e-4032-ac14-35df4646b8b6';
  const recordName = 'MULTI_ROUND_TEST 万科投资与决策委员会的成员组成和会议通知要求分别是什么？';
  const cookieText = fs.readFileSync('../tmp/retry.cookies', 'utf8');
  const line = cookieText.split(/\r?\n/).find(value => value.includes('\tekb_session\t'));
  if (!line) throw new Error('session cookie not found');
  const token = line.split('\t').pop();
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const context = await browser.newContext({ acceptDownloads: true });
    await context.addCookies([{ name: 'ekb_session', value: token, url: `${base}/` }]);
    const page = await context.newPage();
    await page.goto(`${base}/enterprise/history`, { waitUntil: 'networkidle' });
    await page.getByRole('heading', { name: '查询记录', exact: true }).first().waitFor();
    await page.getByText('万科治理验收组B', { exact: true }).first().waitFor();
    await page.getByRole('button', { name: '下载资料包', exact: true }).first().waitFor();
    await page.getByRole('button', { name: '解散组', exact: true }).waitFor();
    await page.getByRole('button', { name: '删除组', exact: true }).waitFor();

    let groupCard = page.getByText('万科治理验收组B', { exact: true }).first()
      .locator('xpath=ancestor::div[.//button[normalize-space()="解散组"] and .//button[normalize-space()="删除组"]][1]');
    await groupCard.getByRole('button', { name: '重命名', exact: true }).click();
    let dialog = page.getByRole('heading', { name: '重命名查询组', exact: true }).locator('xpath=ancestor::div[1]');
    await dialog.locator('input').fill('万科治理浏览器验收B');
    const renamed = page.waitForResponse(response => response.url().includes(`/api/enterprise/query/group?id=${groupId}`) && response.request().method() === 'PATCH' && response.status() === 200);
    await dialog.getByRole('button', { name: '保存', exact: true }).click();
    await renamed;
    await page.getByText('万科治理浏览器验收B', { exact: true }).first().waitFor();

    groupCard = page.getByText('万科治理浏览器验收B', { exact: true }).first()
      .locator('xpath=ancestor::div[.//button[normalize-space()="解散组"] and .//button[normalize-space()="删除组"]][1]');
    await groupCard.getByRole('button', { name: '重命名', exact: true }).click();
    dialog = page.getByRole('heading', { name: '重命名查询组', exact: true }).locator('xpath=ancestor::div[1]');
    await dialog.locator('input').fill('万科治理验收组B');
    const restored = page.waitForResponse(response => response.url().includes(`/api/enterprise/query/group?id=${groupId}`) && response.request().method() === 'PATCH' && response.status() === 200);
    await dialog.getByRole('button', { name: '保存', exact: true }).click();
    await restored;
    await page.getByText('万科治理验收组B', { exact: true }).first().waitFor();

    const row = page.getByText(recordName, { exact: true }).last().locator('xpath=ancestor::div[contains(@class,"sm:p-5")][1]');
    const select = row.locator('select');
    const moved = page.waitForResponse(response => response.url().includes(`/api/enterprise/query/history/record/group?id=${recordId}`) && response.request().method() === 'PATCH' && response.status() === 200);
    await select.selectOption(groupId);
    await moved;
    await page.getByText('万科治理验收组B', { exact: true }).first().waitFor();

    const rowAfterMove = page.getByText(recordName, { exact: true }).last().locator('xpath=ancestor::div[contains(@class,"sm:p-5")][1]');
    const ungrouped = page.waitForResponse(response => response.url().includes(`/api/enterprise/query/history/record/group?id=${recordId}`) && response.request().method() === 'PATCH' && response.status() === 200);
    await rowAfterMove.locator('select').selectOption('');
    await ungrouped;
    const rowAfterUngroup = page.getByText(recordName, { exact: true }).last().locator('xpath=ancestor::div[contains(@class,"sm:p-5")][1]');
    if (await rowAfterUngroup.locator('select').inputValue() !== '') throw new Error('record did not return to ungrouped state');

    const opened = page.waitForURL(url => url.pathname === '/enterprise/ask');
    await rowAfterUngroup.getByRole('button', { name: '打开结果', exact: true }).click();
    await opened;
    await page.getByText('根据检索到的原始资料，委员会的成员组成及会议通知要求均有明确规定。[1]', { exact: true }).waitFor({ timeout: 30000 });

    console.log(JSON.stringify({ historyPage: true, packageVisible: true, renameGroup: true, directGroupSwitch: true, ungroup: true, openHistoricalResult: true }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
