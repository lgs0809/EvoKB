import { test, expect } from '@playwright/test';

for (const restoreFails of [false, true]) {
  test(restoreFails ? 'failed history restoration releases a clean usable input' : 'delayed restoration completes before new question entry and never appends old text', async ({ page }) => {
    await page.addInitScript(() => localStorage.setItem('enterprise-kb-active-query-session', 'previous-session'));
    let finishRestore!: () => void;
    const restoration = new Promise<void>(resolve => { finishRestore = resolve; });
    await page.route('**/api/enterprise/facets', route => route.fulfill({ json: { companies: [], source_departments: [], document_types: [], material_categories: [] } }));
    await page.route('**/api/enterprise/query/sessions?**', route => route.fulfill({ json: { sessions: [], total: 0 } }));
    await page.route('**/api/enterprise/query/session?**', async route => {
      await restoration;
      await route.fulfill(restoreFails
        ? { status: 404, json: { message: 'not available' } }
        : { json: { session_id: 'previous-session', status: 'SUCCEEDED', question: '上次的问题', answer: '上次的答案', citations: [], filters: {} } });
    });
    const submitted: unknown[] = [];
    await page.route('**/api/enterprise/query', route => {
      submitted.push(route.request().postDataJSON());
      return route.fulfill({ json: { session_id: 'new-session', status: 'SUCCEEDED', question: '这是完整的新问题', answer: '新结果', citations: [], filters: {} } });
    });
    await page.goto('/e2e/query-restoration.html');
    const input = page.getByRole('textbox', { name: '你的问题' });
    await expect(page.getByText('正在恢复上次查询', { exact: true })).toBeVisible();
    await expect(input).toBeDisabled();
    await expect(page.getByRole('button', { name: '查询', exact: true })).toBeDisabled();
    finishRestore();
    await expect(input).toBeEnabled();
    await expect(input).toHaveValue(restoreFails ? '' : '上次的问题');
    await input.fill('这是完整的新问题');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    await expect(page.getByText('新结果', { exact: true })).toBeVisible();
    expect(submitted).toEqual([{ question: '这是完整的新问题', filters: {}, limit: 8 }]);
    await expect(input).toHaveValue('这是完整的新问题');
  });
}

test('no saved session makes the query input available immediately', async ({ page }) => {
  await page.route('**/api/enterprise/facets', route => route.fulfill({ json: { companies: [], source_departments: [], document_types: [], material_categories: [] } }));
  await page.route('**/api/enterprise/query/sessions?**', route => route.fulfill({ json: { sessions: [], total: 0 } }));
  await page.goto('/e2e/query-restoration.html');
  await expect(page.getByRole('textbox', { name: '你的问题' })).toBeEnabled();
  await expect(page.getByText('正在恢复上次查询', { exact: true })).toHaveCount(0);
});

test('feedback stays pending until acknowledged and exposes the saved selection', async ({ page }) => {
  await page.route('**/api/enterprise/facets', route => route.fulfill({ json: {} }));
  await page.route('**/api/enterprise/query/sessions?**', route => route.fulfill({ json: {sessions:[]} }));
  await page.route('**/api/enterprise/query', route => route.fulfill({json:{session_id:'answer-1',status:'ANSWERED',answer:'第一份回答',citations:[]}}));
  let release!: () => void;
  const reply = new Promise<void>(resolve => {release=resolve;});
  const posts: unknown[] = [];
  await page.route('**/api/enterprise/query/feedback', async route => {
    posts.push(route.request().postDataJSON());
    await reply;
    await route.fulfill({json:{}});
  });
  await page.goto('/e2e/query-restoration.html');
  await page.getByRole('textbox',{name:'你的问题'}).fill('第一个问题');
  await page.getByRole('button',{name:'查询',exact:true}).click();
  const positive=page.getByRole('button',{name:'已解决',exact:true});
  await positive.click();
  await expect(positive).toBeDisabled();
  await expect(positive).toHaveAttribute('aria-pressed','false');
  await expect(page.getByRole('status')).toHaveText('正在保存反馈…');
  release();
  await expect(page.getByRole('status')).toHaveText('反馈已保存，谢谢。');
  await expect(positive).toHaveAttribute('aria-pressed','true');
  expect(posts).toEqual([{session_id:'answer-1',rating:'POSITIVE',category:'',comment:''}]);
});

test('late feedback acknowledgement cannot mark a different answer as reviewed', async ({ page }) => {
  await page.route('**/api/enterprise/facets', route => route.fulfill({json:{}}));
  await page.route('**/api/enterprise/query/sessions?**', route => route.fulfill({json:{sessions:[]}}));
  let number=0;
  await page.route('**/api/enterprise/query', route => route.fulfill({json:{session_id:`answer-${++number}`,status:'ANSWERED',answer:`回答${number}`,citations:[]}}));
  let release!: () => void;
  const reply=new Promise<void>(resolve=>{release=resolve;});
  await page.route('**/api/enterprise/query/feedback', async route => {await reply;await route.fulfill({json:{}});});
  await page.goto('/e2e/query-restoration.html');
  await page.getByRole('textbox',{name:'你的问题'}).fill('第一个问题');
  await page.getByRole('button',{name:'查询',exact:true}).click();
  await page.getByRole('button',{name:'已解决',exact:true}).click();
  await expect(page.getByRole('status')).toHaveText('正在保存反馈…');
  await page.getByRole('textbox',{name:'你的问题'}).fill('第二个问题');
  await page.getByRole('button',{name:'查询',exact:true}).click();
  await expect(page.getByText('回答2',{exact:true})).toBeVisible();
  release();
  await expect(page.getByRole('button',{name:'已解决',exact:true})).toHaveAttribute('aria-pressed','false');
  await expect(page.getByRole('status')).toHaveText('');
});

test('rejected feedback is not shown as saved and can be retried', async ({ page }) => {
  await page.route('**/api/enterprise/facets', route => route.fulfill({json:{}}));
  await page.route('**/api/enterprise/query/sessions?**', route => route.fulfill({json:{sessions:[]}}));
  await page.route('**/api/enterprise/query', route => route.fulfill({json:{session_id:'answer-1',status:'ANSWERED',answer:'第一份回答',citations:[]}}));
  let attempts=0;
  await page.route('**/api/enterprise/query/feedback', route => route.fulfill(++attempts===1
    ? {status:400,json:{message:'feedback rejected'}} : {json:{}}));
  await page.goto('/e2e/query-restoration.html');
  await page.getByRole('textbox',{name:'你的问题'}).fill('第一个问题');
  await page.getByRole('button',{name:'查询',exact:true}).click();
  const positive=page.getByRole('button',{name:'已解决',exact:true});
  await positive.click();
  await expect(positive).toBeEnabled();
  await expect(positive).toHaveAttribute('aria-pressed','false');
  await expect(page.getByRole('status')).toHaveText('');
  await positive.click();
  await expect(positive).toHaveAttribute('aria-pressed','true');
  await expect(page.getByRole('status')).toHaveText('反馈已保存，谢谢。');
  expect(attempts).toBe(2);
});


for (const status of ['INSUFFICIENT', 'FAILED']) {
  test(`${status} keeps unverified retrieved facts outside the answer and available in a labelled reference area`, async ({ page }) => {
    await page.route('**/api/enterprise/facets', route => route.fulfill({ json: {} }));
    await page.route('**/api/enterprise/query/sessions?**', route => route.fulfill({ json: { sessions: [] } }));
    await page.route('**/api/enterprise/query', route => route.fulfill({ json: {
      session_id: 'unverified-sources', status, evidence_only: true,
      answer: '当前资料不能确认指定对象的金额，仍缺少该对象的权威原文。',
      citations: [{ index: 1, evidence: { evidence_id: 'unrelated-source', version_id: 'other-version',
        source_filename: '另一份资料.md', raw_content: 'OTHER-41 的合同总价为 9999 万元。' } }],
    } }));
    await page.goto('/e2e/query-restoration.html');
    await page.getByRole('textbox', { name: '你的问题' }).fill('指定对象的金额是多少');
    await page.getByRole('button', { name: '查询', exact: true }).click();
    const title = status === 'FAILED' ? '查询未完成' : '查询说明';
    const answer = page.getByRole('region', { name: '查询结果', exact: true });
    await expect(answer.getByText(title, { exact: true })).toBeVisible();
    await expect(answer).toContainText('当前资料不能确认指定对象的金额');
    await expect(answer).not.toContainText('9999 万元');
    await expect(page.getByRole('heading', { name: '已检索资料 1 条' })).toBeVisible();
    await expect(page.getByText('以下是本次检索保留的原文，尚不能据此得出本次问题的完整结论。')).toBeVisible();
    await expect(page.getByRole('button', { name: /另一份资料.md/ })).toContainText('9999 万元');
  });
}
