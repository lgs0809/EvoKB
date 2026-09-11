import type { EnterpriseFacets, EnterpriseSearchFilters } from '../lib/api';
import { Icon } from './ui';

const fields: Array<{ key: keyof EnterpriseSearchFilters; label: string; empty: string; facet: keyof EnterpriseFacets }> = [
  { key: 'source_department', label: '来源部门', empty: '全部部门', facet: 'source_departments' },
  { key: 'document_type', label: '文档类型', empty: '全部类型', facet: 'document_types' },
  { key: 'material_category', label: '材料分类', empty: '全部分类', facet: 'material_categories' },
];

export function KnowledgeScope({ facets, filters, onChange, mode = 'compact' }: {
  facets: EnterpriseFacets;
  filters: EnterpriseSearchFilters;
  onChange: (filters: EnterpriseSearchFilters) => void;
  mode?: 'compact' | 'expanded';
}) {
  const active = fields
    .map(field => ({ label: field.label, value: filters[field.key] as string | undefined }))
    .filter(item => Boolean(item.value));
  const versions = filters.version_ids?.length || 0;
  const scopes = active.map(item => `${item.label}：${item.value}`);
  if (versions) scopes.push(`指定资料：${versions} 个版本`);
  const summary = scopes.length === 0 ? '全部资料' : scopes.join(' · ');
  const hasScope = scopes.length > 0;

  const content = <div class="grid gap-3 sm:grid-cols-3">
    {fields.map(field => <label key={field.key} class="text-xs font-medium text-slate-500 dark:text-slate-400">
      <span class="mb-1.5 block">{field.label}</span>
      <div class="relative">
        <select
          value={(filters[field.key] as string) || ''}
          onChange={event => onChange({ ...filters, [field.key]: (event.target as HTMLSelectElement).value || undefined })}
          class="w-full appearance-none rounded-xl border border-slate-200 bg-white py-2.5 pl-3 pr-10 text-sm text-slate-700 outline-none transition focus:border-blue-400 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-200"
        >
          <option value="">{field.empty}</option>
          {(facets[field.facet] || []).map(value => <option value={value} key={value}>{value}</option>)}
        </select>
        <Icon name="chevron" size={13} class="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 rotate-90 text-slate-400"/>
      </div>
    </label>)}
  </div>;

  if (mode === 'expanded') {
    return <div class="rounded-2xl border border-slate-200 bg-slate-50/70 p-4 dark:border-slate-800 dark:bg-slate-950/50">
      <div class="mb-4 flex items-center justify-between gap-3">
        <div><div class="text-sm font-semibold text-slate-700 dark:text-slate-200">知识范围</div><div class="mt-0.5 text-xs text-slate-400">{summary}</div></div>
        {hasScope && <button onClick={() => onChange({})} class="text-xs font-semibold text-blue-600 hover:text-blue-700 dark:text-blue-400">清空范围</button>}
      </div>
      {content}
    </div>;
  }

  return <details class="group rounded-2xl border border-blue-100 bg-white/85 shadow-sm shadow-blue-100/40 dark:border-white/10 dark:bg-black/15 dark:shadow-none">
    <summary class="flex cursor-pointer list-none items-center justify-between gap-3 px-4 py-3 text-left">
      <div class="flex min-w-0 items-center gap-2 text-xs text-slate-600 dark:text-slate-200"><Icon name="filter" size={14} class="text-blue-500 dark:text-slate-200"/><span class="font-semibold">知识范围</span><span class="truncate text-slate-400">{summary}</span></div>
      <Icon name="chevron" size={13} class="shrink-0 text-slate-400 transition-transform group-open:rotate-90"/>
    </summary>
    <div class="border-t border-blue-100 p-4 dark:border-white/10">{content}{hasScope && <div class="mt-3 text-right"><button onClick={() => onChange({})} class="text-xs font-semibold text-blue-600 hover:text-blue-700 dark:text-blue-300 dark:hover:text-blue-200">清空范围</button></div>}</div>
  </details>;
}
