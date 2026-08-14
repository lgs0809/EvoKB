package wiki

import "strings"

var templates = map[string]Template{
	TypeCompany: {WikiType: TypeCompany, Sections: []TemplateSection{{Name: "Profile"}, {Name: "Ownership and Control"}, {Name: "Governance"}, {Name: "Material Matters"}}},
	TypePerson: {WikiType: TypePerson, Sections: []TemplateSection{{Name: "Identity and Roles"}, {Name: "Ownership and Control"}, {Name: "Material Matters"}}},
	TypeContract: {WikiType: TypeContract, Sections: []TemplateSection{{Name: "Overview"}, {Name: "Parties"}, {Name: "Key Terms"}, {Name: "Performance and Risks"}}},
	TypeLegalCase: {WikiType: TypeLegalCase, Sections: []TemplateSection{{Name: "Overview"}, {Name: "Parties"}, {Name: "Claims and Issues"}, {Name: "Decision and Progress"}}},
	TypeProject: {WikiType: TypeProject, Sections: []TemplateSection{{Name: "Overview"}, {Name: "Key Relationships"}, {Name: "Material Matters"}, {Name: "Risks"}}},
}

func TemplateFor(wikiType string) (Template, bool) {
	key := strings.ToUpper(strings.TrimSpace(wikiType))
	if t, ok := templates[key]; ok {
		return t, true
	}
	if key == TypeControlTopic || key == TypeEquityTopic || key == TypeGenericTopic {
		return Template{WikiType: key, Sections: []TemplateSection{{Name: "Overview"}, {Name: "Key Relationships"}, {Name: "Timeline"}}}, true
	}
	if key == TypeGenericEntity {
		return Template{WikiType: key, Sections: []TemplateSection{{Name: "Overview"}, {Name: "Relationships"}, {Name: "Material Matters"}}}, true
	}
	return Template{}, false
}

func (t Template) HasSection(section string) bool {
	section = strings.TrimSpace(section)
	for _, candidate := range t.Sections {
		if candidate.Name == section {
			return true
		}
	}
	return false
}
