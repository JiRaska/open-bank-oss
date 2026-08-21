// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Server/edge GlitchTip init (Next.js runs register() once at startup) + the
// App-Router server-error hook. No-op when the DSN is unset (ADR-0075 inv. 2).
import * as Sentry from '@sentry/nextjs'
import { buildSentryOptions } from '@/lib/telemetry/glitchtip'

export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs' || process.env.NEXT_RUNTIME === 'edge') {
    Sentry.init(buildSentryOptions('server'))
  }

  // BFF tracing. `nodejs` ONLY, and imported dynamically: the OpenTelemetry NodeSDK pulls in
  // node:async_hooks and other Node built-ins that the edge runtime does not provide, so a
  // static import would break the edge bundle even though the call is guarded. Also a no-op
  // without OTEL_EXPORTER_OTLP_ENDPOINT — see lib/telemetry/tracing.ts for the gating and the
  // reason this is server-side tracing rather than the browser RUM ADR-0088 D4 rejected.
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const { startTracing } = await import('@/lib/telemetry/tracing')
    startTracing()
  }
}

// Captures exceptions thrown in App-Router server components / route handlers.
export const onRequestError = Sentry.captureRequestError
