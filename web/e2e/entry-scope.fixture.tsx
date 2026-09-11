import { render } from 'preact';
import { useState } from 'preact/hooks';
import { KnowledgeEntryBrowser } from '../src/enterprise/KnowledgeEntryBrowser';
import '../src/style.css';

// Explicit synthetic API data supplied by Playwright; no source/model acceptance claim.
function Fixture() {
  const [evidence, setEvidence] = useState('');
  const [error, setError] = useState('');
  return <main class="p-5"><h1>合成条目：来源用途检查</h1><output aria-label="原文选择">{evidence}</output>
    <output aria-label="页面错误">{error}</output><KnowledgeEntryBrowser
      onOpenEvidence={value => setEvidence(value.evidence_id)} onOpenWiki={() => undefined} onError={setError}/></main>;
}
render(<Fixture/>, document.getElementById('fixture')!);
