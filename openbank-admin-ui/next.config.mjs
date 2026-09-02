import { withSentryConfig } from '@sentry/nextjs'
/** @type {import('next').NextConfig} */

const securityHeaders = [
  { key: 'X-Frame-Options', value: 'SAMEORIGIN' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), payment=()' },
  { key: 'Cross-Origin-Opener-Policy', value: 'same-origin' },
  // Content-Security-Policy moved to proxy.ts (ADR-0080 P1 / F-AUTH-06): a per-request
  // nonce + 'strict-dynamic' replaces 'unsafe-inline' on script-src. A static header here can't
  // carry a fresh nonce, so the CSP is emitted by the proxy instead.
]

const nextConfig = {
  // NOTE: `productionBrowserSourceMaps` is deliberately NOT set — see the withSentryConfig block at
  // the bottom of this file. It forces webpack's `devtool: 'source-map'`, which appends a
  // `//# sourceMappingURL=` comment to all ~200 client chunks; @sentry/nextjs instead selects
  // `hidden-source-map`, which writes the same maps for upload but leaves no pointer in the served
  // bundle. Measured on this tree: with the option set, 198 client chunks advertised a .map that had
  // already been deleted after upload — a 404 per chunk in devtools, and an index of exactly where
  // the sources would be if a build ever skipped the delete.
  poweredByHeader: false,
  output: process.env.NEXT_STANDALONE === 'true' ? 'standalone' : undefined,
  // `pg` (node-postgres) uses dynamic requires (pg-native, connection-string).
  // If webpack bundles it into a route, those requires break in the standalone
  // image and the route module fails to load — Next then serves a bare 404 for
  // that endpoint (this is exactly why /api/test-results 404'd in the sandbox).
  // Marking it external keeps it in node_modules and lets outputFileTracing copy
  // it into the standalone bundle intact.
  // The OpenTelemetry packages are external for the SAME reason as `pg` above, plus one
  // that is specific to instrumentation: `@opentelemetry/instrumentation-undici` works by
  // PATCHING undici at module-load time. Bundling changes module identity, so the patch
  // applies to webpack's copy and never to the module Next.js actually calls — the SDK
  // starts, reports nothing wrong, and emits no spans.
  //
  // Measured on the standalone artifact rather than inferred: with only `pg` external,
  // `.next/standalone/node_modules/@opentelemetry` contained exactly ONE entry — `api`.
  // The SDK, the OTLP exporter, the undici instrumentation, resources and
  // semantic-conventions were all bundled away, and the deployed pod produced zero spans
  // while booting cleanly with no error in its log.
  serverExternalPackages: [
    'pg',
    '@opentelemetry/sdk-node',
    '@opentelemetry/exporter-trace-otlp-proto',
    '@opentelemetry/instrumentation-undici',
    '@opentelemetry/instrumentation-http',
    '@opentelemetry/resources',
    '@opentelemetry/semantic-conventions',
    '@opentelemetry/api',
  ],
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
    // This identifies the browser bundle and must therefore be baked at build time. The
    // collector endpoint stays server-side and environment-specific in the relay route.
    NEXT_PUBLIC_BUILD_VERSION: process.env.BUILD_VERSION || 'dev',
  },
  async headers() {
    return [{ source: '/(.*)', headers: securityHeaders }]
  },
}

// Source-map upload to GlitchTip (#3235). This block only does anything under WEBPACK — hence
// `next build --webpack` in package.json. Turbopack emits no client source maps at all (measured on
// Next 16.2.12: `.next/static` had 0 `.map` files while `.next/server` had 1056), and
// `productionBrowserSourceMaps` is read only by `next/dist/build/webpack*`, so under Turbopack there
// is simply nothing for any plugin to upload.
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
  //
  // The three `false`s are a SECURITY choice, not a preference, and each was measured by pointing
  // `sentryUrl` at a request-logging stand-in and reading the traffic rather than the docs. On the
  // defaults sentry-cli calls, per webpack compilation:
  //   POST /api/0/projects/{org}/{proj}/releases/            ← create
  //   PUT  /api/0/projects/{org}/{proj}/releases/{version}/  ← finalize
  //   GET  /api/0/organizations/{org}/repos/                 ← setCommits auto-detect
  // The GlitchTip ingress deliberately allow-lists ingest plus a couple of upload verbs and 404s
  // the rest of the management API, so honouring those would mean exposing a release path that
  // also answers DELETE on a host with no identity gate. Turning them off leaves the upload using
  // ONLY chunk-upload + artifactbundle/assemble — the whole flow fits inside the allow-list, so
  // nothing here depends on a 404 being tolerated.
  //
  // Nothing is lost: GlitchTip's own `assemble_artifacts` does `Release.objects.get_or_create()`
  // from the bundle manifest, so the release still appears — and symbolication does not depend on
  // it either way, it joins events to bundles on the injected debug ID
  // (`javascript_event_processor.transform`).
  release: {
    name: `openbank-admin-ui@${process.env.BUILD_VERSION || 'dev'}`,
    create: false,
    finalize: false,
    setCommits: false,
  },
  sourcemaps: {
    // Uploaded, then deleted from the image: a .map served next to the bundle hands the whole
    // source to anyone who opens the console, which is the opposite of what this is for.
    // The Dockerfile prunes `.next/static/**/*.map` again after the build, so a regression in this
    // option cannot become a source disclosure.
    deleteSourcemapsAfterUpload: true,
  },
  silent: true,
  telemetry: false,
})
