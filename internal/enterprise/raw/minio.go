package raw

import (
	"context"
	"fmt"
	"io"
	"net/url"
	"strings"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

type MinIOStore struct {
	client        *minio.Client
	presignClient *minio.Client
	bucket        string
}

func NewMinIOStore(ctx context.Context, endpoint, accessKey, secretKey, bucket string, useTLS bool) (*MinIOStore, error) {
	return NewMinIOStoreWithPublicEndpoint(ctx, endpoint, "", accessKey, secretKey, bucket, useTLS)
}

func NewMinIOStoreWithPublicEndpoint(ctx context.Context, endpoint, publicEndpoint, accessKey, secretKey, bucket string, useTLS bool) (*MinIOStore, error) {
	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: useTLS,
	})
	if err != nil {
		return nil, fmt.Errorf("minio client: %w", err)
	}
	exists, err := client.BucketExists(ctx, bucket)
	if err != nil {
		return nil, fmt.Errorf("minio bucket exists: %w", err)
	}
	if !exists {
		if err := client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); err != nil {
			return nil, fmt.Errorf("minio make bucket %q: %w", bucket, err)
		}
	}
	presignClient := client
	if strings.TrimSpace(publicEndpoint) != "" && strings.TrimSpace(publicEndpoint) != strings.TrimSpace(endpoint) {
		region, regionErr := client.GetBucketLocation(ctx, bucket)
		if regionErr != nil {
			return nil, fmt.Errorf("minio bucket location: %w", regionErr)
		}
		presignClient, err = minio.New(publicEndpoint, &minio.Options{
			Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
			Secure: useTLS,
			Region: region,
		})
		if err != nil {
			return nil, fmt.Errorf("minio public client: %w", err)
		}
	}
	return &MinIOStore{client: client, presignClient: presignClient, bucket: bucket}, nil
}

func (s *MinIOStore) Put(ctx context.Context, key string, r io.Reader, size int64, contentType string) error {
	_, err := s.client.PutObject(ctx, s.bucket, key, r, size, minio.PutObjectOptions{ContentType: contentType})
	if err != nil {
		return fmt.Errorf("minio put %q: %w", key, err)
	}
	return nil
}

func (s *MinIOStore) Get(ctx context.Context, key string) (io.ReadCloser, error) {
	obj, err := s.client.GetObject(ctx, s.bucket, key, minio.GetObjectOptions{})
	if err != nil {
		return nil, fmt.Errorf("minio get %q: %w", key, err)
	}
	if _, err := obj.Stat(); err != nil {
		_ = obj.Close()
		return nil, fmt.Errorf("minio get %q stat: %w", key, err)
	}
	return obj, nil
}

func (s *MinIOStore) Stat(ctx context.Context, key string) (ObjectInfo, error) {
	st, err := s.client.StatObject(ctx, s.bucket, key, minio.StatObjectOptions{})
	if err != nil {
		return ObjectInfo{}, fmt.Errorf("minio stat %q: %w", key, err)
	}
	return ObjectInfo{Key: key, Size: st.Size, ContentType: st.ContentType, ETag: st.ETag}, nil
}

func (s *MinIOStore) PresignGet(ctx context.Context, key string, expiry time.Duration) (string, error) {
	if expiry <= 0 {
		expiry = 15 * time.Minute
	}
	client := s.presignClient
	if client == nil {
		client = s.client
	}
	u, err := client.PresignedGetObject(ctx, s.bucket, key, expiry, url.Values{})
	if err != nil {
		return "", fmt.Errorf("minio presign %q: %w", key, err)
	}
	return u.String(), nil
}
