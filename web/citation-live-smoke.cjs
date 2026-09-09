const fs = require('fs');
const { chromium } = require('@playwright/test');

(async () => {
  const sessionId = 'd0c4b980-ff2e-4a67-8553-c6510727bc5b';
  const cookieFile = fs.readFileSync('../tmp/retry.cookies', 'utf8');
  const line = cookieFile.split(/\r?\n/).find((v) => v.includes('\tekb_session\t'));
  if (!line) throw new Error('session cookie not found');
  const token = line.split('\t').pop();
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const context = await browser.newContext();
    await context.addCookies([{ name: 'ekb_session', value: token, url: 'http://127.0.0.1:18080/' }]);
    const page = await context.newPage();
    await page.addInitScript((id) => localStorage.setItem('enterprise-kb-active-query-session', id), sessionId);
    await page.goto('http://127.0.0.1:18080/enterprise/ask', { waitUntil: 'networkidle' });

    await page.getByText('你澄清的是投资与决策委员会；原始资料中有对应成员组成规定。[1]', { exact: true }).waitFor();
    await page.getByText('参考资料 1 条', { exact: true }).waitFor();
    await page.getByText('查看原文', { exact: true }).click();
    await page.getByRole('heading', { name: '原文查看' }).waitFor();
    await page.getByText('参考位置：第 1 页', { exact: true }).waitFor();
    await page.getByText('证据位置', { exact: true }).waitFor({ timeout: 30000 });
    const canvas = page.locator('canvas');
    await canvas.waitFor({ state: 'visible', timeout: 30000 });
    const box = await canvas.boundingBox();
    if (!box || box.width < 100 || box.height < 100) throw new Error('PDF canvas did not render');
    const errorText = await page.locator('body').innerText();
    if (errorText.includes('无法加载原文件') || errorText.includes('InvalidPDFException')) throw new Error('PDF viewer reported an error');
    console.log(JSON.stringify({ sessionId, restored: true, citations: 1, referencePage: 1, highlight: true, canvas: { width: Math.round(box.width), height: Math.round(box.height) } }));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
