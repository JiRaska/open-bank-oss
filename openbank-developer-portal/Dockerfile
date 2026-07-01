# OpenBank Developer Portal — public PSD2/XS2A docs (static, zero-secret).
# Unprivileged nginx (non-root uid 101, listens on 8080) so the pod runs under the
# restricted Pod Security Standard with a read-only root filesystem.
FROM nginxinc/nginx-unprivileged:1.31-alpine

# Drop the stock server config; ours listens on 8080 with the security headers + strict CSP.
COPY nginx.conf /etc/nginx/nginx.conf

# Static content: landing/docs + the Berlin XS2A OpenAPI and the vendored Redoc renderer
# (no external CDN at runtime — keeps the strict same-origin CSP intact).
COPY site/ /usr/share/nginx/html/

EXPOSE 8080
