import { test, expect } from '@playwright/test';

for (const legacy of [false, true]) {
  test(legacy ? 'legacy missing scope proof is explicit without inventing evidence' : 'scope-only evidence opens its own source while description stays separate', async ({ page }) => {
    const source = { document_id: 'synthetic-document', version_id: 'synthetic-version', version_no: 1,
      logical_name: '合成合同', summary_id: 'synthetic-summary', concept_id: 'synthetic-concept',
      facts: [{ fact_id: 'description-fact', fact_text: '质保期为24个月。', evidence_ids: ['description-evidence'] }],
      scope_facts: legacy ? [] : [{ fact_id: 'scope-fact', fact_text: '本合成为协议SCOPE-321。', evidence_ids: ['scope-evidence'] }] };
    const entry = { entry_id: 'synthetic-entry', revision: 2, entry_type: 'CONTRACT', label: '合成质保期', aliases: [],
      description: '质保期为24个月。', stage: 'CONCEPT', lifecycle: 'ACTIVE',
      scope: { identity_status: 'SCOPE_GROUNDED', anchors: [{ type: 'CONTRACT', value: 'scope-321' }] },
      updated_at: '2026-11-02T15:00:00Z', source_count: 1, sources: [source] };
    await page.route('**/api/enterprise/knowledge-entries?**', route => route.fulfill({ json: { entries: [entry], total: 1, page: 1, page_size: 30 } }));
    await page.route('**/api/enterprise/knowledge-entry?**', route => route.fulfill({ json: entry }));
    const evidenceRequests: string[] = [];
    await page.route('**/api/enterprise/evidence?**', route => {
      const id = new URL(route.request().url()).searchParams.get('id')!;
      evidenceRequests.push(id); return route.fulfill({ json: { evidence_id: id, raw_content: 'Explicit synthetic source fixture' } });
    });
    await page.goto('/e2e/entry-scope.html');
    await expect(page.getByRole('heading', { name: '合成质保期', exact: true })).toBeVisible();
    if (legacy) {
      await expect(page.getByText(/部分历史来源未保存专用范围依据/)).toBeVisible();
      await expect(page.getByRole('button', { name: '查看范围原文 1' })).toHaveCount(0);
      expect(evidenceRequests).toEqual([]);
    } else {
      await expect(page.getByText('适用范围依据', { exact: true })).toBeVisible();
      await expect(page.getByText(source.scope_facts[0].fact_text, { exact: true })).toBeVisible();
      await page.getByRole('button', { name: '查看范围原文 1' }).click();
      await expect(page.getByLabel('原文选择')).toHaveText('scope-evidence');
      await page.getByRole('button', { name: '查看原文 1', exact: true }).click();
      await expect(page.getByLabel('原文选择')).toHaveText('description-evidence');
      expect(evidenceRequests).toEqual(['scope-evidence', 'description-evidence']);
    }
    await expect(page.getByLabel('页面错误')).toBeEmpty();
  });
}
