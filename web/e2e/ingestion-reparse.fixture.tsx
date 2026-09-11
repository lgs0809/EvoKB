import { render } from 'preact';
import { useState } from 'preact/hooks';
import { IngestionHistoryPage } from '../src/enterprise/IngestionHistoryPage';
import '../src/style.css';

// Production UI with explicit synthetic API fixtures; no real parse/model pass is claimed.
function Fixture() {
  const [error, setError] = useState('');
  const admin = new URLSearchParams(window.location.search).get('reader') !== '1';
  return <main class="p-5"><h1>合成任务：历史解析失败恢复</h1><output aria-label="页面提示">{error}</output>
    <IngestionHistoryPage isAdmin={admin} securityClearance={10} canUpload={false} onNavigate={() => undefined} onError={setError}/></main>;
}
render(<Fixture/>,document.getElementById('fixture')!);
