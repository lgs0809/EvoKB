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
    const admin = await browser.newContext();
    await admin.addCookies([{ name: 'ekb_session', value: tokenFrom('../tmp/main-admin.cookies'), url: `${base}/` }]);
    const page = await admin.newPage();
    await page.goto(`${base}/enterprise?ts=${Date.now()}`, { waitUntil: 'networkidle' });
    await page.getByRole('button', { name: '模型配置', exact: true }).waitFor({ timeout: 30000 });
    await page.getByRole('button', { name: '查询记录', exact: true }).waitFor();

    await page.getByRole('button', { name: '模型配置', exact: true }).click();
    await page.getByRole('heading', { name: '模型配置', exact: true }).first().waitFor();
    await page.getByText('LLM / Chat', { exact: true }).waitFor();
    await page.getByText('Embedding', { exact: true }).waitFor();
    await page.getByText('Rerank', { exact: true }).waitFor();

    await page.getByRole('button', { name: '入库记录', exact: true }).click();
    await page.getByText(/排队中 \d+/).first().waitFor({ timeout: 30000 });
    const oldQueued = await page.getByText('已排队', { exact: true }).count();

    await page.getByRole('button', { name: '知识查询', exact: true }).click();
    const companyScope = await page.getByText('全部公司', { exact: true }).count();

    console.log(JSON.stringify({
      modelConfigurationVisible: true,
      queryHistoryVisible: true,
      modelCardsVisible: true,
      queuedLabelVisible: true,
      oldQueuedLabelCount: oldQueued,
      companyScopeCount: companyScope,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
