#!/usr/bin/env bash
# Publish only the reviewed static status assets to their private CDN origin.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_DIR="$HERE/../../aws/envs/web-prod"
export AWS_PROFILE="${AWS_PROFILE:-openbank}"

BUCKET="$(cd "$ENV_DIR" && tofu output -raw status_bucket)"
DIST="$(cd "$ENV_DIR" && tofu output -raw status_distribution_id)"

aws s3 sync "$HERE" "s3://$BUCKET" \
  --delete \
  --exclude "*.html" \
  --exclude "*.md" \
  --exclude "*.sh" \
  --exclude "robots.txt" \
  --exclude "llms.txt" \
  --exclude "sitemap.xml" \
  --exclude ".well-known/*" \
  --exclude "backend/*" \
  --exclude ".DS_Store" \
  --cache-control "public, max-age=86400"

aws s3 cp "$HERE/index.html" "s3://$BUCKET/index.html" \
  --content-type "text/html; charset=utf-8" \
  --cache-control "public, max-age=0, must-revalidate"

for name in robots.txt llms.txt .well-known/security.txt; do
  aws s3 cp "$HERE/$name" "s3://$BUCKET/$name" \
    --content-type "text/plain; charset=utf-8" \
    --cache-control "public, max-age=86400"
done
aws s3 cp "$HERE/sitemap.xml" "s3://$BUCKET/sitemap.xml" \
  --content-type "application/xml; charset=utf-8" \
  --cache-control "public, max-age=86400"

aws cloudfront create-invalidation --distribution-id "$DIST" --paths "/*" >/dev/null
echo "Deployed https://status.open-bank.tech/"
