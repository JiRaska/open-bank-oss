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

  // BFF tracing. The SDK itself is NOT started here: `@opentelemetry/instrumentation-http`
  // patches `node:http` at require time, and by the time Next.js calls register() the standalone
  // server has already loaded it — measured 2026-08-21 as zero exported spans. It is started by
  // otel-bootstrap.cjs, preloaded via NODE_OPTIONS=--require. This import exists so Next.js file
  // tracing copies the OpenTelemetry packages into the standalone output, which is what makes
  // that preload resolvable at runtime. `nodejs` only and dynamic: the NodeSDK pulls in
  // node:async_hooks and other built-ins the edge runtime does not provide.
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    await import('@/lib/telemetry/tracing')
  }
}

// Captures exceptions thrown in App-Router server components / route handlers.
export const onRequestError = Sentry.captureRequestError
