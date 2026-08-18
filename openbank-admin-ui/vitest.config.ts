// SPDX-License-Identifier: Apache-2.0
import { defineConfig } from 'vitest/config'
import path from 'path'

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
      // Pinned just BELOW the level ACTUALLY MEASURED when coverage was first
      // switched on (2026-07-16, full suite):
      //   statements 36.53% · branches 18.78% · functions 29.98% · lines 38.79%
      // For scale: without the 81-page render-smoke suite the same run measures
      // statements 7.03% · branches 4.09% · functions 5.30% · lines 7.40% —
      // mounting the pages is what moved it, and these floors would collapse back
      // to single digits if that suite ever stopped being collected.
      // The floors sit ~0.5–1pt under each measurement so ordinary churn doesn't
      // red-CI a good PR. They are a floor, NOT a target: raise them as real
      // coverage rises; never lower one to make CI pass.
      thresholds: {
        statements: 36,
        branches: 18,
        functions: 29,
        lines: 38,
      },
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
      // `server-only` is a Next.js RSC marker package not present in node_modules;
      // stub it so server-only libs (e.g. src/lib/docs/releases.ts) import under Vitest.
      'server-only': path.resolve(__dirname, './src/test/stubs/server-only.ts'),
    },
  },
})
