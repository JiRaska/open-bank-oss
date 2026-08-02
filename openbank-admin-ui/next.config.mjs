import { withSentryConfig } from '@sentry/nextjs'
/** @type {import('next').NextConfig} */

const securityHeaders = [
  { key: 'X-Frame-Options', value: 'SAMEORIGIN' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), payment=()' },
  { key: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
  // Content-Security-Policy moved to middleware.ts (ADR-0080 P1 / F-AUTH-06): a per-request
  // nonce + 'strict-dynamic' replaces 'unsafe-inline' on script-src. A static header here can't
  // carry a fresh nonce, so the CSP is emitted by the middleware instead.
]

const nextConfig = {
  productionBrowserSourceMaps: true,
  poweredByHeader: false,
  output: process.env.NEXT_STANDALONE === 'true' ? 'standalone' : undefined,
  // `pg` (node-postgres) uses dynamic requires (pg-native, connection-string).
  // If webpack bundles it into a route, those requires break in the standalone
  // image and the route module fails to load — Next then serves a bare 404 for
  // that endpoint (this is exactly why /api/test-results 404'd in the sandbox).
  // Marking it external keeps it in node_modules and lets outputFileTracing copy
  // it into the standalone bundle intact.
  serverExternalPackages: ['pg'],
  env: {
    NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL || 'http://localhost:8100',
    // Default to the public https host (consistent with KC_URL above) — never a cleartext
    // localhost fallback baked into the bundle. Local dev sets NEXT_PUBLIC_KEYCLOAK_URL in .env.local.
    NEXT_PUBLIC_KEYCLOAK_URL: process.env.NEXT_PUBLIC_KEYCLOAK_URL || 'https://kc.open-bank.tech',
    NEXT_PUBLIC_KEYCLOAK_REALM: process.env.NEXT_PUBLIC_KEYCLOAK_REALM || 'openbank',
    NEXT_PUBLIC_KEYCLOAK_CLIENT_ID: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID || 'openbank-admin-ui',
    // GlitchTip crash/error monitoring (ADR-0075). NEXT_PUBLIC_* is inlined at BUILD time,
    // so the DSN ships as the default here (it's a public client ingest key, not a secret —
    // same as the mobile app baking its DSN). Internal open-bank.tech origin, never sentry.io.
    NEXT_PUBLIC_GLITCHTIP_DSN: process.env.NEXT_PUBLIC_GLITCHTIP_DSN || 'https://58123562c22f4cb98ab9f2023d828f93@glitchtip.open-bank.tech/2',
    NEXT_PUBLIC_GLITCHTIP_ENVIRONMENT: process.env.NEXT_PUBLIC_GLITCHTIP_ENVIRONMENT || 'sandbox',
    // Derived from BUILD_VERSION, never a literal. It was pinned at 0.29.0 while version.txt said
    // 0.80.1 — fifty-one minors stale — and a release string that names a build nobody ships is not
    // cosmetic once source maps exist: maps are filed under a release, events are tagged with one,
    // and symbolication is the join between them. A stale literal silently guarantees a miss.
    NEXT_PUBLIC_GLITCHTIP_RELEASE:
      process.env.NEXT_PUBLIC_GLITCHTIP_RELEASE ||
      `openbank-admin-ui@${process.env.BUILD_VERSION || 'dev'}`,
  },
  async headers() {
    return [{ source: '/(.*)', headers: securityHeaders }]
  },
}

// Upload wiring for GlitchTip (#3235) — a PREREQUISITE, not the fix.
//
// Measured on Next 16.2.12: this build runs Turbopack, which emits no CLIENT source maps
// (`.next/static` has zero `.map` files, with or without a token) — `productionBrowserSourceMaps` is
// a webpack-era option Turbopack ignores, and @sentry/nextjs says as much about its own hooks. So
// there is currently nothing to upload and this block is a no-op until that is solved.
//
// It is here because the token plumbing and the release naming are the parts that CAN be got right
// now, and because the alternative — wiring it later together with the Turbopack work — is how the
// release string came to be pinned at a version nobody shipped.
//
// `telemetry: false` is not optional here — the plugin phones home to sentry.io by default, and this
// estate reports to a self-hosted GlitchTip on an internal origin precisely so it does not.
//
// No token (local dev, PR builds) means no upload and a normal build: `withSentryConfig` skips the
// upload step rather than failing, so this cannot become a reason a build breaks.
export default withSentryConfig(nextConfig, {
  org: 'openbank',
  project: 'openbank-admin-ui',
  sentryUrl: 'https://glitchtip.open-bank.tech',
  authToken: process.env.SENTRY_AUTH_TOKEN,
  // Must equal what the SDK tags events with, or maps and events never join.
  release: { name: `openbank-admin-ui@${process.env.BUILD_VERSION || 'dev'}` },
  sourcemaps: {
    // Uploaded, then deleted from the image: a .map served next to the bundle hands the whole
    // source to anyone who opens the console, which is the opposite of what this is for.
    deleteSourcemapsAfterUpload: true,
  },
  silent: true,
  telemetry: false,
})
