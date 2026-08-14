FROM node:24-alpine AS web-build
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM golang:1.26-alpine AS go-build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /out/enterprise-kb ./cmd/enterprise-kb

FROM alpine:3.23
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app
COPY --from=go-build /out/enterprise-kb /usr/local/bin/enterprise-kb
COPY --from=web-build /src/internal/web/dist /app/internal/web/dist
EXPOSE 18080
ENTRYPOINT ["/usr/local/bin/enterprise-kb"]
CMD ["server", "--addr", "0.0.0.0:18080", "--web-dir", "/app/internal/web/dist"]
