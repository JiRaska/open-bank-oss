// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Browser-side GlitchTip init (Next.js App Router runs this before hydration).
// No-op when the DSN is unset (ADR-0075 inv. 2). See lib/telemetry/glitchtip.ts.
import * as Sentry from '@sentry/nextjs'
import { buildSentryOptions } from '@/lib/telemetry/glitchtip'

Sentry.init(buildSentryOptions('browser'))

// Links client-side App-Router navigations to their pageload/transaction (Next 15.3+/16).
export const onRouterTransitionStart = Sentry.captureRouterTransitionStart
