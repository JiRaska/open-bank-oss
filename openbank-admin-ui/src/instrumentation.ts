// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

// Server/edge GlitchTip init (Next.js runs register() once at startup) + the
// App-Router server-error hook. No-op when the DSN is unset (ADR-0075 inv. 2).
import * as Sentry from '@sentry/nextjs'
import { buildSentryOptions } from '@/lib/telemetry/glitchtip'

export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs' || process.env.NEXT_RUNTIME === 'edge') {
    Sentry.init(buildSentryOptions('server'))
  }
}

// Captures exceptions thrown in App-Router server components / route handlers.
export const onRequestError = Sentry.captureRequestError
