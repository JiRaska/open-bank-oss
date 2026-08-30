// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * The browser RUM exporter's `service.name` (issue #7536).
 *
 * Deliberately distinct from `openbank-admin-ui`, which the BFF's own server-side OpenTelemetry
 * SDK (`otel-bootstrap.cjs`) already exports under. Every route except `/auth`, `/privacy` and
 * `/.well-known/` answers a 307 before any handler runs (`src/proxy.ts`, ADR-0080 P0), so the BFF
 * emits a span for that traffic whether or not a browser tab ever exported anything. Sharing one
 * `service.name` meant a Tempo/Prometheus query for "did browser telemetry arrive" was satisfied
 * by inbound BFF traffic alone, so it could never observe absence — the exact "loud detector,
 * silently useless" shape this fleet has been burned by before.
 *
 * Kept in its own module with no OpenTelemetry SDK imports so a Node-only route
 * (`api/test-intelligence/route.ts`) can read the value without pulling in
 * `@opentelemetry/sdk-trace-web`.
 */
export const RUM_SERVICE_NAME = 'openbank-admin-ui-browser'
