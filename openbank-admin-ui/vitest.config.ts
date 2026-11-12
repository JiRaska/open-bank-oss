// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitest/config'
import path from 'path'

const aliases = {
  '@': path.resolve(__dirname, './src'),
  // `server-only` is a Next.js RSC marker package absent from node_modules.
  'server-only': path.resolve(__dirname, './src/test/stubs/server-only.ts'),
}

// These suites depend on process-level mocking, CJS/ESM loading, or timing that
// Vitest's isolated VM worker cannot reproduce. Keep their established fork pool.
const forkOnly = [
  'src/test/loyalty-console.guard.test.ts',
  'src/test/mermaid-labels.test.ts',
  'src/test/markdown-view-security.test.tsx',
  'src/test/audience-library.test.tsx',
  'src/test/compliance-packs-console.test.tsx',
  'src/test/document-template-editor-dialog.test.tsx',
  'src/test/document-template-lifecycle-dialog.test.tsx',
  'src/test/iaops-rca-route.test.ts',
  'src/test/render-smoke.test.tsx',
]

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // e2e/ is Playwright territory — Vitest only runs src/test/**.
    // `.tsx` MUST stay in this glob: it was `.ts`-only until the render-smoke
    // work, which meant any component/page test authored as `.tsx` was silently
    // never collected — no error, no skip, just zero runs. `render-smoke.test.tsx`
    // is itself the executable proof the glob is right: if `.tsx` collection
    // regresses, the 81-page smoke suite vanishes and coverage drops through the
    // ratchet below, so the failure is loud instead of silent.
    include: ['src/test/**/*.{test,spec}.{ts,tsx}'],
    projects: [
      { test: { name: 'isolated-vm', pool: 'vmThreads', exclude: forkOnly } },
      {
        extends: false,
        resolve: { alias: aliases },
        test: {
          name: 'process-forks', pool: 'forks', environment: 'jsdom', globals: true,
          setupFiles: ['./src/test/setup.ts'], include: forkOnly,
        },
      },
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary', 'html'],
      reportsDirectory: './coverage',
      // Source extensions ONLY. A bare 'src/**' also sweeps in the data files
      // that live under src (.bpmn diagrams, registry .json, .css), which v8 then
      // tries to parse as JavaScript — a wall of PARSE_ERROR noise, plus a skewed
      // denominator as each data file is counted 0%-covered.
      include: ['src/**/*.{ts,tsx}'],
      // Exclusions are code with no meaningful unit-testable branch surface:
      // type-only decls, the test tree itself, and Next.js build/config plumbing.
      exclude: [
        'src/test/**',
        'src/**/*.d.ts',
        'src/types/**',
        'src/proxy.ts',
        'src/instrumentation*.ts',
      ],
      // ── Ratchet (repo convention: coverage may never go down) ──────────────
      // ADR-0370 U3. Until 2026-11-11 these floors were 36/18/29/38 and CI never ran with
      // --coverage, so they were enforced nowhere. Measured that day on the full suite:
      //   statements 73.3% · branches 62.22% · functions 69.2% · lines 77.26%
      // Each floor sits 1pt (floored) under its measurement so ordinary churn does not red a
      // good PR. The global floor alone lets decision logic lose tests while page smoke tests
      // keep the total up, so each src/lib decision module carries its own floor as well.
      // Floors only go up: gate admin-ui-module-coverage-ratchet fails a lowered or removed
      // one unless the PR carries `coverage-floor-lowered: #<issue>`.
      thresholds: {
        statements: 72,
        branches: 61,
        functions: 68,
        lines: 76,
        'src/lib/aml/**': { statements: 92, branches: 87, functions: 99, lines: 99 },
        'src/lib/approvals/**': { statements: 94, branches: 92, functions: 99, lines: 99 },
        'src/lib/auth/**': { statements: 85, branches: 80, functions: 81, lines: 87 },
        'src/lib/closings/**': { statements: 90, branches: 90, functions: 95, lines: 99 },
        'src/lib/consents/**': { statements: 88, branches: 85, functions: 99, lines: 96 },
        'src/lib/delegations/**': { statements: 90, branches: 79, functions: 99, lines: 93 },
        'src/lib/disputes/**': { statements: 87, branches: 91, functions: 99, lines: 99 },
        'src/lib/fees/**': { statements: 85, branches: 74, functions: 99, lines: 99 },
        'src/lib/format/**': { statements: 89, branches: 99, functions: 99, lines: 89 },
        'src/lib/fraud/**': { statements: 85, branches: 83, functions: 99, lines: 99 },
        'src/lib/interest/**': { statements: 83, branches: 80, functions: 99, lines: 99 },
        'src/lib/ledger/**': { statements: 80, branches: 80, functions: 90, lines: 93 },
        'src/lib/lending/**': { statements: 95, branches: 99, functions: 99, lines: 93 },
        'src/lib/mutations/**': { statements: 96, branches: 87, functions: 99, lines: 96 },
        'src/lib/payments/**': { statements: 93, branches: 90, functions: 99, lines: 99 },
        'src/lib/sanctions/**': { statements: 79, branches: 92, functions: 65, lines: 84 },
        'src/lib/settlement/**': { statements: 99, branches: 93, functions: 99, lines: 99 },
      },
    },
  },
  resolve: { alias: aliases },
})
