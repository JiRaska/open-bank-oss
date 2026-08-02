# Built OUT OF BAND — nothing in this repository builds this image.
#
# Unlike the 48 stripped in #3249, this file is a genuine, self-contained recipe: it needs no
# Gradle build stage and no ADR-0122 sibling COPYs, so `docker build` on this directory works.
# But no workflow, no `openbank-infra/scripts/*` and no Makefile invokes it — the running image
# (`openbank-developer-portal`) was produced somewhere outside this tree, and nothing connects the two.
#
# So "there is an image in ECR" is NOT evidence that this file produced it. Before trusting this
# recipe, confirm the deployed digest actually came from it; before editing it, remember that a
# change here reaches nothing until someone rebuilds by hand.
#
# Fixing this properly means pointing a workflow at it (the way build-push-admin-ui.sh does for
# admin-ui). Recorded rather than silently left, so the next reader does not mistake a deployed
# artifact for a live pipeline.

# OpenBank Developer Portal — public PSD2/XS2A docs (static, zero-secret).
# Unprivileged nginx (non-root uid 101, listens on 8080) so the pod runs under the
# restricted Pod Security Standard with a read-only root filesystem.
FROM nginxinc/nginx-unprivileged:1.31-alpine@sha256:a8d5564c3354241473c1e152d5dd3281ab4224edb61b23c291e0bfd9854687a1

# Drop the stock server config; ours listens on 8080 with the security headers + strict CSP.
COPY nginx.conf /etc/nginx/nginx.conf

# Static content: landing/docs + the Berlin XS2A OpenAPI and the vendored Redoc renderer
# (no external CDN at runtime — keeps the strict same-origin CSP intact).
COPY site/ /usr/share/nginx/html/

EXPOSE 8080
