package retrieval

import (
	"crypto/rand"
	"encoding/hex"
)

func newSearchID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "search"
	}
	return "search-" + hex.EncodeToString(b[:])
}
