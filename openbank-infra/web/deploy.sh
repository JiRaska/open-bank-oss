#!/usr/bin/env bash
# Sync the static landing site to its S3 origin and invalidate CloudFront.
# Idempotent. Reads bucket + distribution id from the web-prod tofu state.
#
#   AWS_PROFILE=openbank ./deploy.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SITE="$HERE/landing"
ENV_DIR="$HERE/../aws/envs/web-prod"
export AWS_PROFILE="${AWS_PROFILE:-openbank}"

BUCKET="$(cd "$ENV_DIR" && tofu output -raw bucket)"
DIST="$(cd "$ENV_DIR" && tofu output -raw distribution_id)"
echo "→ bucket=$BUCKET  distribution=$DIST"

# 1) Long-lived, fingerprint-free assets (img/css/js/fonts) — cache hard.
aws s3 sync "$SITE" "s3://$BUCKET" \
  --delete \
  --exclude "*.html" \
  --exclude "*.txt" \
  --exclude "*.xml" \
  --exclude "*.md" \
  --exclude ".DS_Store" \
  --cache-control "public, max-age=86400"

# 2) HTML — always revalidate so content changes show immediately.
aws s3 sync "$SITE" "s3://$BUCKET" \
  --exclude "*" \
  --include "*.html" \
  --content-type "text/html; charset=utf-8" \
  --cache-control "public, max-age=0, must-revalidate"

# 3) Discovery / well-known text files (robots.txt, security.txt, llms.txt, ai.txt).
#    Short TTL: security.txt carries an Expires field; robots.txt changes mean recrawl.
aws s3 sync "$SITE" "s3://$BUCKET" \
  --exclude "*" \
  --include "*.txt" \
  --include ".well-known/*" \
  --content-type "text/plain; charset=utf-8" \
  --cache-control "public, max-age=3600"

# 4) Sitemap — explicit application/xml so Google Search Console accepts it.
aws s3 sync "$SITE" "s3://$BUCKET" \
  --exclude "*" \
  --include "*.xml" \
  --content-type "application/xml; charset=utf-8" \
  --cache-control "public, max-age=3600"

# 5) Bust the edge cache.
aws cloudfront create-invalidation --distribution-id "$DIST" --paths "/*" >/dev/null
echo "✓ deployed — https://open-bank.tech"
