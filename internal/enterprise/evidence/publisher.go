package evidence

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"time"

	"github.com/xoai/sage-wiki/internal/enterprise/raw"
)

type Publisher struct {
	Packager *Packager
	Objects  raw.ObjectStore
	Expiry   time.Duration
}

func (p *Publisher) Publish(ctx context.Context, evidenceIDs []string) (PublishedPackage, error) {
	if p == nil || p.Packager == nil || p.Objects == nil {
		return PublishedPackage{}, fmt.Errorf("package publisher dependencies are required")
	}
	f, err := os.CreateTemp("", "enterprise-kb-sources-*.zip")
	if err != nil {
		return PublishedPackage{}, err
	}
	name := f.Name()
	defer os.Remove(name)
	result, err := p.Packager.StreamZIP(ctx, evidenceIDs, f)
	if err != nil {
		_ = f.Close()
		return PublishedPackage{}, err
	}
	if err := f.Close(); err != nil {
		return PublishedPackage{}, err
	}
	return p.upload(ctx, name, result)
}

func (p *Publisher) upload(ctx context.Context, filename string, result PackageResult) (PublishedPackage, error) {
	f, err := os.Open(filename)
	if err != nil { return PublishedPackage{}, err }
	defer f.Close()
	st, err := f.Stat()
	if err != nil { return PublishedPackage{}, err }
	id := packageID()
	key := "packages/" + id + ".zip"
	if err := p.Objects.Put(ctx, key, f, st.Size(), "application/zip"); err != nil { return PublishedPackage{}, err }
	expiry := p.Expiry
	if expiry <= 0 { expiry = 15 * time.Minute }
	u, err := p.Objects.PresignGet(ctx, key, expiry)
	if err != nil { return PublishedPackage{}, err }
	return PublishedPackage{PackageID: id, ObjectKey: key, DownloadURL: u, ExpiresAt: time.Now().UTC().Add(expiry), Manifest: result.Manifest}, nil
}

func packageID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil { return fmt.Sprintf("pkg-%d", time.Now().UTC().UnixNano()) }
	return "pkg-" + hex.EncodeToString(b[:])
}
