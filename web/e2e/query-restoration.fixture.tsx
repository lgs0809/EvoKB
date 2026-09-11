import { render } from 'preact';
import { AskPage } from '../src/enterprise/AskPage';
import '../src/style.css';

// This component fixture exercises controlled network timing, never real model output.
render(<AskPage onOpenEvidence={() => undefined} onError={() => undefined}/>, document.getElementById('fixture')!);
