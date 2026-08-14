package enterprisemcp

import (
	"github.com/mark3labs/mcp-go/server"
	enterpriseapi "github.com/xoai/sage-wiki/internal/enterprise/api"
	enterpriseevidence "github.com/xoai/sage-wiki/internal/enterprise/evidence"
)

type Server struct {
	mcp       *server.MCPServer
	api       *enterpriseapi.Service
	publisher *enterpriseevidence.Publisher
}

func NewServer(api *enterpriseapi.Service, publisher *enterpriseevidence.Publisher) *Server {
	s := &Server{api: api, publisher: publisher}
	s.mcp = server.NewMCPServer("enterprise-knowledge-base", "dev", server.WithToolCapabilities(false))
	s.registerTools()
	return s
}

func (s *Server) MCPServer() *server.MCPServer { return s.mcp }

func (s *Server) ServeStdio() error { return server.ServeStdio(s.mcp) }
