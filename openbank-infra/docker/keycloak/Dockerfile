# syntax=docker/dockerfile:1
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
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

# EXTENSION (ADR-0385 D3, #16667): extension/ holds the bank's Keycloak providers — the MCP
# withheld-code authenticator and the MCP client-guard client-policy executor. It is compiled
# here, against the jars of THIS pinned Keycloak image and nothing else: no dependency is
# resolved, and the SPI it compiles against is byte-for-byte the SPI it runs on, so a Keycloak
# bump that breaks it fails this build instead of a login. Its unit tests run in the same stage
# (a failing test fails the image build). The provider must be in /opt/keycloak/providers BEFORE
# `kc.sh build`, which is why it is copied into the builder below.

ARG KEYCLOAK_VERSION=26.8.2-3

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3 AS keycloak-libs

# The repo's pinned JDK (also standalone-service.Dockerfile). Bytecode is architecture-neutral, so
# this stage runs on the build host's platform; --release 21 matches the image's Java 21 runtime.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk@sha256:68868d04fa9cfd5f5c6abec0b5cef86d8de2bf9c62c37c7d3e4f0f80f5cfd7ff AS extension
COPY --from=keycloak-libs /opt/keycloak/lib/lib/main /kc/main
COPY --from=keycloak-libs /opt/keycloak/lib/lib/boot /kc/boot
COPY extension/src /src
RUN set -eu; cd /src; \
    javac --release 21 -Xlint:all -Werror -cp '/kc/main/*:/kc/boot/*' -d /out/main $(find main/java -name '*.java'); \
    cp -R main/resources/. /out/main/; \
    javac --release 21 -Xlint:all -Werror -cp '/out/main:/kc/main/*:/kc/boot/*' -d /out/test $(find test/java -name '*.java'); \
    java -cp '/out/test:/out/main:/kc/main/*:/kc/boot/*' com.openbank.keycloak.mcp.ExtensionTests test/resources/mcp-connect-context.fixture.json; \
    java -cp '/out/test:/out/main:/kc/main/*:/kc/boot/*' com.openbank.keycloak.mcp.McpReadDelegationRequestTests test/resources/mcp-read-client.pem.fixture; \
    java -cp '/out/test:/out/main:/kc/main/*:/kc/boot/*' com.openbank.keycloak.mcp.McpReadDelegationEvidenceTests; \
    java -cp '/out/test:/out/main:/kc/main/*:/kc/boot/*' com.openbank.keycloak.mcp.McpConnectContextV2Tests test/resources/mcp-connect-context-v2.fixture.json; \
    java -cp '/out/test:/out/main:/kc/main/*:/kc/boot/*' com.openbank.keycloak.mcp.McpBalancePermitClientTests; \
    java -cp '/out/test:/out/main:/kc/main/*:/kc/boot/*' com.openbank.keycloak.mcp.McpReadDelegationFactoryTests; \
    java -cp '/out/test:/out/main:/kc/main/*:/kc/boot/*' com.openbank.keycloak.mcp.McpConnectAuthorityClientTests; \
    jar --create --file /out/openbank-keycloak-mcp.jar -C /out/main .

# The jar alone, for the live-Keycloak IT (`docker buildx build --target extension-jar -o ...`).
# Unreferenced by the image, so BuildKit skips it in a normal build.
FROM scratch AS extension-jar
COPY --from=extension /out/openbank-keycloak-mcp.jar /

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3 AS builder
COPY --from=extension /out/openbank-keycloak-mcp.jar /opt/keycloak/providers/openbank-keycloak-mcp.jar
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
ENV KC_CACHE=local
ENV KC_FEATURES=token-exchange,admin-fine-grained-authz:v1
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3
# The augmented server (and its baked persisted-config) is the whole /opt/keycloak
# tree — copy it wholesale, the approach Keycloak's own optimized-image docs use.
COPY --from=builder /opt/keycloak/ /opt/keycloak/
USER 1000
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
