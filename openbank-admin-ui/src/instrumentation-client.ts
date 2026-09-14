// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Browser-side GlitchTip init (Next.js App Router runs this before hydration).
// Keep the sizeable SDK out of Next's root bundle: starting the import immediately still
// installs monitoring during bootstrap, while hydration can parse and execute the product UI
// without first paying the SDK's cost. The shared promise also preserves ordering when a route
// transition starts before the import has settled.
import { buildSentryOptions } from '@/lib/telemetry/glitchtip'

type RouterTransitionStart = typeof import('@sentry/nextjs').captureRouterTransitionStart

const sentryReady = import('@sentry/nextjs').then((Sentry) => {
  Sentry.init(buildSentryOptions('browser'))
  return Sentry
})

// Links client-side App-Router navigations to their pageload/transaction (Next 15.3+/16).
export const onRouterTransitionStart: RouterTransitionStart = (...args) => {
  void sentryReady.then((Sentry) => Sentry.captureRouterTransitionStart(...args))
}
