# syntax=docker/dockerfile:1
# SPDX-License-Identifier: MPL-2.0
# Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
#
# Pre-optimized Keycloak image for the sandbox IdP (iam namespace).
#
# WHY: the stock `quay.io/keycloak/keycloak` image re-runs `kc.sh build`
# (augmentation) on every container start because build-time options are passed
# as runtime env. That adds ~40-50s to every cold start, so a pod move (Karpenter
# consolidation, node failure, version/theme roll) takes the single-replica IdP
# offline long enough for the login loop to 503. Baking `kc.sh build` into the
# image and starting with `--optimized` cuts cold start from ~70s to ~10s. No
# extra running cost — same single replica, just a prebuilt image (FinOps-neutral).
#
# CONTRACT: every ENV below is a Keycloak BUILD-TIME option. They MUST stay in
# sync with what the live deployment used to set at runtime; `start --optimized`
# refuses to start if a runtime-provided build option disagrees with the baked
# one, so the deployment (gitops/components/keycloak/keycloak.yaml) MUST NOT pass
# any of these as env anymore — they live here now. Runtime-only options
# (KC_HOSTNAME, KC_PROXY_HEADERS, KC_HTTP_ENABLED, KC_DB_URL/USERNAME/PASSWORD,
# bootstrap admin) stay as Deployment env.
#
# KC_FEATURES note: token-exchange + admin-fine-grained-authz:v1 were enabled on
# the live pod (mobile/SCA token exchange, ADR-0021/0065) but had drifted out of
# gitops. Baking them here re-declares them as code — this Dockerfile is now the
# source of truth for the feature set.

ARG KEYCLOAK_VERSION=26.6.3

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS builder
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_CACHE=local
ENV KC_FEATURES=token-exchange,admin-fine-grained-authz:v1
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
# The augmented server (and its baked persisted-config) is the whole /opt/keycloak
# tree — copy it wholesale, the approach Keycloak's own optimized-image docs use.
COPY --from=builder /opt/keycloak/ /opt/keycloak/
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
