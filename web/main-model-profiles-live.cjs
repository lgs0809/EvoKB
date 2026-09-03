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
    const context = await browser.newContext();
    await context.addCookies([{ name: 'ekb_session', value: tokenFrom('../tmp/main-admin.cookies'), url: `${base}/` }]);
    const page = await context.newPage();
    await page.goto(`${base}/enterprise?ts=${Date.now()}`, { waitUntil: 'networkidle' });

    await page.getByRole('button', { name: '查询记录', exact: true }).waitFor();
    const modelBeforeExpand = await page.getByRole('button', { name: '模型配置', exact: true }).count();
    await page.getByRole('button', { name: '管理', exact: true }).click();
    await page.getByRole('button', { name: '模型配置', exact: true }).waitFor();
    await page.getByRole('button', { name: '模型配置', exact: true }).click();
    await page.getByRole('heading', { name: '模型配置', exact: true }).first().waitFor();
    await page.getByText('默认配置', { exact: true }).first().waitFor();
    await page.getByText('使用中', { exact: true }).waitFor();
    await page.getByText('LLM / Chat', { exact: true }).waitFor();
    await page.getByText('Embedding', { exact: true }).waitFor();
    await page.getByText('Rerank', { exact: true }).waitFor();
    await page.getByText('已保存：', { exact: false }).first().waitFor();

    await page.getByRole('button', { name: '复制配置', exact: true }).click();
    await page.getByText('默认配置 副本', { exact: true }).first().waitFor();
    await page.getByRole('button', { name: '设为当前使用', exact: true }).waitFor();
    await page.getByRole('button', { name: '删除', exact: true }).click();
    await page.getByRole('button', { name: '删除配置组', exact: true }).click();
    await page.getByText('默认配置 副本', { exact: true }).waitFor({ state: 'detached' });

    await page.getByRole('button', { name: '入库记录', exact: true }).click();
    await page.getByText(/排队中 \d+/).first().waitFor();
    const oldQueued = await page.getByText('已排队', { exact: true }).count();

    console.log(JSON.stringify({
      managementCollapsedInitially: modelBeforeExpand === 0,
      modelProfilePageVisible: true,
      activeProfileVisible: true,
      modelSectionsVisible: true,
      maskedKeyVisible: true,
      copyAndDeleteProfileWorks: true,
      oldQueuedLabelCount: oldQueued,
    }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
