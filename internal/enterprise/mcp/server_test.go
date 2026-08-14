package enterprisemcp

import "testing"

func TestServerExposesOnlyStableEnterpriseTools(t *testing.T) {
	srv := NewServer(nil, nil)
	tools := srv.MCPServer().ListTools()
	if len(tools) != 2 {
		t.Fatalf("expected exactly 2 enterprise tools, got %d", len(tools))
	}
	for _, name := range []string{"search_knowledge", "package_sources"} {
		if _, ok := tools[name]; !ok {
			t.Fatalf("missing tool %s", name)
		}
	}
}
