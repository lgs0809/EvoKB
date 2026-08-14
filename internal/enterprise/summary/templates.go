package summary

import "strings"

var templates = map[string]Template{
	DocumentTypeAnnualReport: template(DocumentTypeAnnualReport,
		req("主体与报告期", "报告主体、报告期和编制口径"),
		req("财务摘要", "收入、利润、资产负债、现金流及重要同比变化"),
		req("经营情况", "主营业务、重要业务变化、关键经营数据"),
		req("股权与控制", "股东、持股比例、实际控制人及重要变化"),
		opt("重大合同与债务", "重大合同、融资、担保和或有负债"),
		opt("诉讼与合规", "重大诉讼仲裁、行政处罚和合规事项"),
		req("风险事项", "报告明确披露的重大风险和不确定性")),
	DocumentTypeAuditReport: template(DocumentTypeAuditReport,
		req("审计主体与期间", "被审计主体、财务报表期间"),
		req("审计意见", "无保留、保留、否定或无法表示意见及其结论"),
		opt("形成意见的基础", "形成审计意见的关键依据、保留事项"),
		opt("关键审计事项", "关键审计事项及应对"),
		req("关键财务数据", "审计报告中明确出现的重要金额和科目"),
		opt("强调与持续经营", "强调事项、持续经营重大不确定性"),
		req("审计机构", "会计师事务所、签字人员和报告日期")),
	DocumentTypeContract: template(DocumentTypeContract,
		req("合同主体", "签约方、关联主体和角色"),
		req("标的与范围", "交易、服务、资产或权利义务标的"),
		opt("金额与付款", "合同金额、币种、计价、付款节点和条件"),
		req("期限与时间节点", "生效、履行、交付、续期和终止期限"),
		req("核心权利义务", "各方主要权利、义务和前置条件"),
		opt("担保与增信", "保证、抵押、质押或其他增信"),
		req("违约与解除", "违约责任、解除、终止和赔偿"),
		req("争议解决", "法律适用、管辖、仲裁或诉讼约定")),
	DocumentTypeLegal: template(DocumentTypeLegal,
		req("案件主体", "原告、被告、申请人、被申请人及其他当事人"),
		req("案号与机构", "案号、法院、仲裁机构或行政机关"),
		req("争议与请求", "诉讼仲裁请求、争议焦点和标的"),
		req("事实与理由", "主要事实、依据和争议背景"),
		req("裁判或进展", "判决、裁定、调解、仲裁结果或当前进展"),
		opt("涉案金额", "诉请、判赔、执行或保全金额"),
		opt("执行与期限", "履行、上诉、执行、保全等时间节点"),
		req("风险影响", "文档明确体现的业务、财务或合规影响")),
	DocumentTypePolicy: template(DocumentTypePolicy,
		req("适用范围", "适用主体、业务和场景"),
		req("生效与版本", "制定、修订、生效、废止日期及版本"),
		req("核心规则", "必须遵守的主要规则、标准和条件"),
		req("职责与权限", "部门、岗位和决策权限"),
		opt("流程与记录", "审批、执行、留痕和报告流程"),
		opt("限制与禁止", "禁止性、限制性和例外规定"),
		opt("责任与处罚", "违规责任、问责或处罚")),
	DocumentTypeProject: template(DocumentTypeProject,
		req("项目与主体", "项目名称、主体、背景和范围"),
		req("业务与资产", "业务模式、核心资产、产品或服务"),
		opt("关键经营与财务数据", "收入、成本、客户、产能等关键数据"),
		opt("客户供应商与合同", "重要客户、供应商、合同和依赖"),
		opt("组织与股权", "组织结构、股权和控制关系"),
		req("重要问题与风险", "尽调发现的问题、异常、依赖和风险"),
		opt("结论与建议", "材料明确给出的结论、条件和建议")),
	DocumentTypeEquityControl: template(DocumentTypeEquityControl,
		req("变更主体", "公司、股东、受让方及相关主体"),
		req("变更前股权", "变更前持股比例和控制关系"),
		req("变更后股权", "变更后持股比例和控制关系"),
		req("控制权变化", "实际控制人、共同控制或无控制人变化"),
		opt("交易对价", "股权数量、价格、总对价和支付安排"),
		req("时间节点", "协议、交割、登记和生效日期"),
		opt("审批与条件", "董事会、股东会、监管审批和先决条件")),
	DocumentTypeResolution: template(DocumentTypeResolution,
		req("会议信息", "会议类型、时间、地点和召集主持情况"),
		req("出席与表决", "出席主体、表决权和表决结果"),
		req("议案", "审议议案和主要内容"),
		req("决议", "通过、否决或附条件通过的事项"),
		opt("授权", "对董事、高管或其他主体的授权"),
		opt("生效与后续", "决议生效条件和后续执行要求")),
	DocumentTypeFinancingGuarantee: template(DocumentTypeFinancingGuarantee,
		req("融资主体与机构", "借款人、贷款人、债权人及相关主体"),
		req("金额与币种", "融资、授信或担保金额及币种"),
		req("期限与利率", "期限、利率、费用和调整机制"),
		opt("资金用途", "融资用途和使用限制"),
		req("担保安排", "保证、抵押、质押、担保范围和顺位"),
		opt("偿还与触发", "还款安排、提前到期、交叉违约等触发条件"),
		opt("审批与条件", "内部审批、授信条件和其他先决条件")),
	DocumentTypeLicenseIP: template(DocumentTypeLicenseIP,
		req("权利主体", "持有人、申请人、许可人或被许可人"),
		req("名称与编号", "资质、许可证、专利、商标、著作权名称及编号"),
		req("权利范围", "业务、商品、技术、地域或使用范围"),
		req("有效期与状态", "申请、授权、生效、到期、续期和当前状态"),
		opt("限制与条件", "限制、附加条件、年检或持续合规要求"),
		opt("许可质押与处分", "许可、转让、质押和其他权利负担")),
	DocumentTypeGeneralMixed: template(DocumentTypeGeneralMixed,
		req("主体", "文档涉及的核心企业、人员和其他主体"),
		req("时间与背景", "关键日期、期间和事件背景"),
		req("核心事实", "文档明确陈述的主要事实"),
		opt("金额与比例", "重要金额、比例和数量"),
		opt("权利义务与关系", "主体之间的重要关系、权利和义务"),
		req("风险与异常", "明确披露的风险、限制、争议和异常")),
}

func template(docType string, sections ...TemplateSection) Template {
	return Template{DocumentType: docType, Sections: sections}
}

func req(name, description string) TemplateSection {
	return TemplateSection{Name: name, Description: description, Required: true}
}

func opt(name, description string) TemplateSection {
	return TemplateSection{Name: name, Description: description}
}

func TemplateFor(documentType string) Template {
	if t, ok := templates[strings.TrimSpace(documentType)]; ok {
		return t
	}
	return templates[DocumentTypeGeneralMixed]
}

func IsAllowedDocumentType(documentType string) bool {
	_, ok := templates[strings.TrimSpace(documentType)]
	return ok
}

func (t Template) HasSection(section string) bool {
	section = strings.TrimSpace(section)
	for _, s := range t.Sections {
		if s.Name == section {
			return true
		}
	}
	return false
}

func (t Template) RequiredSections() []string {
	out := make([]string, 0, len(t.Sections))
	for _, s := range t.Sections {
		if s.Required {
			out = append(out, s.Name)
		}
	}
	return out
}
