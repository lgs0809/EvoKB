package summary

import (
	"regexp"
	"sort"
	"strings"
	"unicode"
)

var criticalAtomPatterns = []*regexp.Regexp{
	regexp.MustCompile(`(?i)(?:人民币|RMB|CNY|¥|￥|美元|USD|\$)?\s*\d[\d,]*(?:\.\d+)?\s*(?:亿元|万元|元|万美元|美元)`),
	regexp.MustCompile(`\d+(?:\.\d+)?\s*%`),
	regexp.MustCompile(`\d{4}\s*[年/-]\s*\d{1,2}\s*[月/-]\s*\d{1,2}\s*日?`),
	regexp.MustCompile(`(?:案号|合同编号|协议编号|许可证号|证书编号|统一社会信用代码|专利号|商标号|项目编号)\s*[:：]?\s*[A-Za-z0-9（）()\-_/\.一-龥]{2,64}`),
	regexp.MustCompile(`[A-Za-z0-9一-龥（）()·]{2,48}(?:股份有限公司|集团有限公司|有限责任公司|有限公司)`),
	regexp.MustCompile(`(?:法定代表人|董事长|总经理|负责人|签字人|原告|被告|申请人|被申请人|姓名)\s*[:：]?\s*[一-龥·]{2,8}`),
}

func unsupportedCriticalAtoms(factText, evidenceText string) []string {
	evidenceNormalized := normalizeSupportText(evidenceText)
	seen := map[string]struct{}{}
	var out []string
	for _, re := range criticalAtomPatterns {
		for _, atom := range re.FindAllString(factText, -1) {
			n := normalizeSupportText(atom)
			if n == "" {
				continue
			}
			if _, ok := seen[n]; ok {
				continue
			}
			seen[n] = struct{}{}
			if !strings.Contains(evidenceNormalized, n) {
				out = append(out, strings.TrimSpace(atom))
			}
		}
	}
	sort.Strings(out)
	return out
}

func normalizeSupportText(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(strings.TrimSpace(s)) {
		if unicode.IsSpace(r) {
			continue
		}
		switch r {
		case ',', '，', '。', '；', ';', '：', ':', '“', '”', '"', '\'', '（', '）', '(', ')':
			continue
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}
