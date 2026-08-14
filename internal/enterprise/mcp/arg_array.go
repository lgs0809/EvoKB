package enterprisemcp

func argStrings(args map[string]any, key string) []string {
	values, _ := args[key].([]any)
	out := make([]string, 0, len(values))
	for _, value := range values {
		text, ok := value.(string)
		if ok && text != "" {
			out = append(out, text)
		}
	}
	return out
}
