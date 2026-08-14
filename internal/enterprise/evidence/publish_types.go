package evidence

import "time"

type PublishedPackage struct {
	PackageID   string          `json:"package_id"`
	ObjectKey   string          `json:"object_key"`
	DownloadURL string          `json:"download_url"`
	ExpiresAt   time.Time       `json:"expires_at"`
	Manifest    PackageManifest `json:"manifest"`
}
