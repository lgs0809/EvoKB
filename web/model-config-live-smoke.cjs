const fs = require('fs');
const { chromium } = require('@playwright/test');

(async () => {
  const cookieFile = fs.readFileSync('../tmp/retry.cookies', 'utf8');
  const line = cookieFile.split(/\r?\n/).find((v) => v.includes('\tekb_session\t'));
  if (!line) throw new Error('session cookie not found');
  const token = line.split('\t').pop();
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const context = await browser.newContext();
    await context.addCookies([{ name: 'ekb_session', value: token, url: 'http://127.0.0.1:28081/' }]);
    const page = await context.newPage();
    await page.goto('http://127.0.0.1:28081/enterprise/models', { waitUntil: 'networkidle' });
    await page.getByRole('main').getByRole('heading', { name: '模型配置' }).waitFor();
    const small = page.getByLabel('Small Model');
    const large = page.getByLabel('Large Model');
    const timeout = page.getByLabel('请求超时（秒）').first();
    if (await small.inputValue() !== 'gpt-5.4-mini') throw new Error('unexpected small model');
    if (await large.inputValue() !== 'gpt-5.6-luna') throw new Error('unexpected large model');
    if (await timeout.inputValue() !== '120') throw new Error('unexpected chat timeout');
    const configuredBadges = await page.getByText('已配置', { exact: true }).count();
    if (configuredBadges < 1) throw new Error('configured API key badge missing');
    const bodyText = await page.locator('body').innerText();
    if (!bodyText.includes('更换 Embedding 模型或维度后')) throw new Error('embedding dimension warning missing');

    await timeout.fill('121');
    const saved121 = page.waitForResponse((r) => r.url().endsWith('/api/enterprise/model-configuration') && r.request().method() === 'PUT' && r.status() === 200);
    await page.getByRole('button', { name: '保存并立即生效' }).click();
    await saved121;
    if (await timeout.inputValue() !== '121') throw new Error('browser save to 121 did not stick');

    await timeout.fill('120');
    const saved120 = page.waitForResponse((r) => r.url().endsWith('/api/enterprise/model-configuration') && r.request().method() === 'PUT' && r.status() === 200);
    await page.getByRole('button', { name: '保存并立即生效' }).click();
    await saved120;
    if (await timeout.inputValue() !== '120') throw new Error('browser restore to 120 did not stick');

    console.log(JSON.stringify({ page: 'models', small: await small.inputValue(), large: await large.inputValue(), timeout: await timeout.inputValue(), configuredBadges, browserPutRoundTrip: true }));
  } finally {
    await browser.close();
  }
})().catch((error) => { console.error(error); process.exit(1); });
