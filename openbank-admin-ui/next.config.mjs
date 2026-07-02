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
    NEXT_PUBLIC_GLITCHTIP_RELEASE: process.env.NEXT_PUBLIC_GLITCHTIP_RELEASE || 'openbank-admin-ui@0.29.0',
  },
  async headers() {
    return [{ source: '/(.*)', headers: securityHeaders }]
  },
}

export default nextConfig
