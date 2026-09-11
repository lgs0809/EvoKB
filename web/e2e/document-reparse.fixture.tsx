import { render } from 'preact';
import { useState } from 'preact/hooks';
import { DocumentsPage } from '../src/enterprise/DocumentsPage';
import { PageRefreshContext } from '../src/enterprise/PageRefresh';
import '../src/style.css';

// Production page with explicit synthetic API fixtures supplied by Playwright routes.
// This verifies UI interaction; it does not ingest sources or certify Raw publication.
function Fixture() {
  const [error, setError] = useState('');
  const [destination, setDestination] = useState('');
  const [refresh, setRefresh] = useState(0);
  const reader = new URLSearchParams(window.location.search).get('reader') === '1';
  return <main class="p-5"><h1>合成资料：重新解析交互检查</h1>
    {error && <output aria-label="页面提示">{error}</output>}
    <output aria-label="导航目的地">{destination}</output>
    <button onClick={() => setRefresh(value => value + 1)}>刷新当前页面</button>
    <PageRefreshContext.Provider value={refresh}>
      <DocumentsPage canUpload={!reader} isAdmin={!reader} onNavigate={setDestination}
        onError={setError} onOpenEvidence={() => undefined} onChanged={() => undefined}/>
    </PageRefreshContext.Provider>
  </main>;
}
render(<Fixture/>, document.getElementById('fixture')!);
