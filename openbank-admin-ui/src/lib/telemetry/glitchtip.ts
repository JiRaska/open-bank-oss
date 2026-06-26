// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.

import type { BrowserOptions } from '@sentry/nextjs'
import type { ErrorEvent, EventHint } from '@sentry/core'

/**
 * GlitchTip (Sentry-protocol) crash/error monitoring for the admin console (ADR-0075),
 * mirroring the mobile app's CrashMonitor: capture the operator-side failures the backend
 * LGTM stack can't see (unhandled render exceptions, API-route crashes, pre-fetch errors)
 * into the self-hosted GlitchTip sink — never sentry.io.
 *
 * GATED (inv. 2): a blank DSN makes Sentry.init a no-op, so monitoring is off unless
 * NEXT_PUBLIC_GLITCHTIP_DSN is set (baked at build via next.config.mjs; it's a public
 * client ingest key, not a secret). PII (inv. 3): [scrub] strips the identifying fields
 * — email/username/ip, cookies, auth headers, query strings — before any event leaves
 * the browser/server. This is a bank operator console: URLs and headers carry tokens and
 * customer ids, so the redaction is deliberately aggressive.
 */
const DSN = process.env.NEXT_PUBLIC_GLITCHTIP_DSN || ''
const ENVIRONMENT = process.env.NEXT_PUBLIC_GLITCHTIP_ENVIRONMENT || 'sandbox'
const RELEASE = process.env.NEXT_PUBLIC_GLITCHTIP_RELEASE || undefined

// Browser noise that is NOT an admin-console fault — dropped before it reaches the sink so
// the operator sees only real failures. These are third-party-extension and empty-rejection
// artifacts the browser SDK captures by default (observed live in GlitchTip): a crypto-wallet
// extension's "Failed to connect to MetaMask", and "Object captured as promise rejection with
// keys: [object has no keys]" — a `Promise.reject({})` with no message, almost always from an
// injected extension script or an aborted fetch, never actionable.
const NOISE_PATTERNS = [
  // MetaMask-branded only — NOT a bare /Failed to connect/, which would also drop real
  // "Failed to connect to <backend>/Keycloak" errors operators need to see.
  /MetaMask/i,
  /Object captured as promise rejection with keys: \[object has no keys\]/i,
  /Non-Error promise rejection captured/i,
  // Transient code-splitting failure: a client holding an OLD tab across an admin-ui
  // deploy requests a chunk hash that no longer exists on the new build. Self-heals on
  // reload (Next.js auto-reloads on a fresh navigation) — not an admin-console fault, but
  // it kept recurring in GlitchTip after #1183 because it isn't extension/empty noise.
  /ChunkLoadError/i,
  /Loading chunk [\w-]+ failed/i,
  /Loading CSS chunk [\w-]+ failed/i,
]
const EXTENSION_URL = /^(chrome|moz|safari-web|safari)-extension:\/\//i

/** True when this event is third-party browser noise rather than an admin-console error. */
function isNoise(event: ErrorEvent, hint: EventHint): boolean {
  const msg = event.message ?? event.exception?.values?.[0]?.value ?? ''
  if (NOISE_PATTERNS.some((re) => re.test(msg))) return true
  // Empty object thrown/rejected ({} with no own keys) — no signal, only noise.
  const orig = hint?.originalException
  if (orig && typeof orig === 'object' && !(orig instanceof Error) && Object.keys(orig).length === 0) {
    return true
  }
  // Frames or request originating from a browser extension, not our bundle.
  if (event.request?.url && EXTENSION_URL.test(event.request.url)) return true
  const frames = event.exception?.values?.flatMap((v) => v.stacktrace?.frames ?? []) ?? []
  if (frames.some((f) => f.filename && EXTENSION_URL.test(f.filename))) return true
  return false
}

/** Strip identifying fields before an event is sent (ADR-0075 inv. 3). */
function scrub(event: ErrorEvent, _hint: EventHint): ErrorEvent | null {
  if (event.user) {
    delete event.user.email
    delete event.user.username
    delete event.user.ip_address
  }
  if (event.request) {
    delete event.request.cookies
    delete event.request.query_string
    delete event.request.data
    if (event.request.headers) {
      for (const h of ['authorization', 'cookie', 'x-csrf-token', 'set-cookie']) {
        delete event.request.headers[h]
        delete event.request.headers[h.replace(/(^|-)([a-z])/g, (_m, p, c) => p + c.toUpperCase())]
      }
    }
  }
  // A bank URL path/query carries account/party ids and one-time tokens — keep the route
  // shape but drop the query the SDK may have attached to the transaction name.
  if (event.request?.url) event.request.url = event.request.url.split('?')[0]
  return event
}

/**
 * Shared Sentry/GlitchTip options for both runtimes. Errors-only (no performance/RUM —
 * tracesSampleRate 0) to match the mobile app's crash-focused posture and keep the
 * self-hosted sink lean (ADR-0027).
 */
export function buildSentryOptions(runtime: 'browser' | 'server'): BrowserOptions {
  return {
    dsn: DSN,
    environment: ENVIRONMENT,
    release: RELEASE,
    enabled: DSN.length > 0,
    tracesSampleRate: 0,
    sendDefaultPii: false,
    // The screenshot/replay integrations would capture a banking screen — never enable.
    attachStacktrace: true,
    initialScope: { tags: { runtime } },
    // Drop third-party browser noise (extensions, empty rejections) BEFORE scrubbing PII, so the
    // operator's GlitchTip shows only real admin-console failures.
    beforeSend: (event, hint) => (isNoise(event, hint) ? null : scrub(event, hint)),
    // Belt-and-suspenders: the eventFilters integration drops these even when beforeSend can't
    // see a structured message (e.g. message-only events), and excludes extension-origin frames.
    ignoreErrors: NOISE_PATTERNS,
    denyUrls: [EXTENSION_URL],
  }
}
