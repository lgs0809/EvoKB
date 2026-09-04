import DOMPurify from 'dompurify';
import { Marked } from 'marked';
import markedCjkFriendly from 'marked-cjk-friendly';
import { useMemo } from 'preact/hooks';

const FORMATTING_TAGS = [
  'p', 'br', 'strong', 'em', 'del', 'h1', 'h2', 'h3', 'h4',
  'ul', 'ol', 'li', 'blockquote', 'pre', 'code', 'hr',
  'table', 'thead', 'tbody', 'tr', 'th', 'td',
];
// Use the parser's CJK extension for adjacent Chinese punctuation; retain original model text.
const markdown = new Marked(markedCjkFriendly());

/** Model content is untrusted. Render formatting only; source actions use authenticated UI controls. */
export function KnowledgeMarkdown({ value, tableSpans = false }: { value: string; tableSpans?: boolean }) {
  const html = useMemo(() => DOMPurify.sanitize(
    markdown.parse(value, { async: false, gfm: true, breaks: true }),
    { ALLOWED_TAGS: FORMATTING_TAGS, ALLOWED_ATTR: tableSpans ? ['colspan', 'rowspan'] : [], ALLOW_DATA_ATTR: false, ALLOW_ARIA_ATTR: false },
  ), [value, tableSpans]);
  return <div
    data-testid="knowledge-markdown"
    class="break-words text-[15px] leading-8 text-slate-700 dark:text-slate-200 [&>p]:mb-4 [&>p:last-child]:mb-0 [&_strong]:font-semibold [&_strong]:text-slate-900 dark:[&_strong]:text-slate-100 [&_ul]:mb-4 [&_ul]:list-disc [&_ul]:pl-6 [&_ol]:mb-4 [&_ol]:list-decimal [&_ol]:pl-6 [&_li]:mb-2 [&_h1]:mb-4 [&_h1]:text-xl [&_h1]:font-semibold [&_h2]:mb-3 [&_h2]:text-lg [&_h2]:font-semibold [&_h3]:mb-2 [&_h3]:font-semibold [&_h4]:font-semibold [&_blockquote]:mb-4 [&_blockquote]:border-l-4 [&_blockquote]:border-blue-200 [&_blockquote]:pl-4 [&_pre]:mb-4 [&_pre]:overflow-x-auto [&_pre]:rounded-lg [&_pre]:bg-slate-100 [&_pre]:p-4 dark:[&_pre]:bg-slate-950 [&_code]:font-mono [&_code]:text-sm [&_table]:my-4 [&_table]:w-full [&_table]:border-collapse [&_th]:border [&_th]:border-slate-200 [&_th]:bg-slate-50 [&_th]:p-2 [&_td]:border [&_td]:border-slate-200 [&_td]:p-2 dark:[&_th]:border-slate-700 dark:[&_td]:border-slate-700 dark:[&_th]:bg-slate-800"
    dangerouslySetInnerHTML={{ __html: html }}
  />;
}
