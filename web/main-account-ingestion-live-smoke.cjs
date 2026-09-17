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
    const ap = await admin.newPage();
    await ap.goto(`${base}/enterprise/ingestion`, { waitUntil: 'networkidle' });
    await ap.getByRole('heading', { name: '入库记录', exact: true }).first().waitFor();
    await ap.getByText('15 个文件', { exact: true }).first().waitFor();
    await ap.getByText('lgs', { exact: true }).first().waitFor();
    await ap.getByText('lgs1', { exact: true }).first().waitFor();

    const user = await browser.newContext();
    await user.addCookies([{ name: 'ekb_session', value: tokenFrom('../tmp/main-user.cookies'), url: `${base}/` }]);
    const up = await user.newPage();
    await up.goto(`${base}/enterprise/ingestion`, { waitUntil: 'networkidle' });
    await up.getByRole('heading', { name: '上传记录', exact: true }).first().waitFor();
    await up.getByText('1 个文件', { exact: true }).first().waitFor();
    await up.getByText('万科投资与决策委员会实施细则（2025年6月修订）', { exact: true }).first().waitFor({ timeout: 30000 });

    console.log(JSON.stringify({ adminVisible: true, adminSeesBothOwners: true, adminBatch15Visible: true, userVisible: true, userOwnUploadVisible: true }));
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exit(1); });
