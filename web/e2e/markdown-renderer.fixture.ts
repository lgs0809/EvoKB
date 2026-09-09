import { h, render } from 'preact';
import { KnowledgeMarkdown } from '../src/enterprise/KnowledgeMarkdown';

const normal = '# 合同条款\n\n验收后 **15 日** 付款。[1]\n\n- 需要合法发票\n- 质保 24 个月\n\n| 条件 | 数值 |\n| --- | --- |\n| 合同总价 | 900 万元 |'
  + '\n\n**验收标准：**设备完成试运行。中文**强调（条件）。**紧接中文。'
  + '\n\n**日本語。**続く文。 **한국어(조건)**다음문장。'
  + '\n\n**English bold** text. `**literal code**` \\*\\*escaped markers\\*\\*';
const untrusted = '正文仍可阅读。[2]\n\n<img src="/renderer-test-missing" onerror="document.body.dataset.executed=\'true\'">'
  + '<a href="javascript:document.body.dataset.executed=\'true\'">不可信链接文本</a>'
  + '<form><input name="secret"><button>不可信操作</button></form>'
  + '<svg onload="document.body.dataset.executed=\'true\'"><script>document.body.dataset.executed="true"</script></svg>';

render(h(KnowledgeMarkdown, { value: normal }), document.getElementById('normal-content')!);
render(h(KnowledgeMarkdown, { value: untrusted }), document.getElementById('untrusted-content')!);
const rawTable = '<table style="background:red" onclick="document.body.dataset.executed=\'true\'">'
  + '<tbody><tr><th colspan="2">采购明细</th></tr><tr><td rowspan="2">合同 HT-ENTRY-104</td><td>900 万元</td></tr>'
  + '<tr><td data-secret="must-not-survive" onmouseover="document.body.dataset.executed=\'true\'">尾款条件完整保留</td></tr></tbody></table>'
  + '<script>document.body.dataset.executed="true"</script><img src="/renderer-test-missing">';
render(h(KnowledgeMarkdown, { value: rawTable, tableSpans: true }), document.getElementById('raw-table-content')!);
