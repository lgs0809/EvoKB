package enterprisemcp

func argString(args map[string]any, key string) string {
	value, _ := args[key].(string)
	return value
}

func argInt(args map[string]any, key string) int {
	value, _ := args[key].(float64)
	return int(value)
}
