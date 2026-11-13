// SPDX-License-Identifier: Apache-2.0
// ADR-0370 U5 — mutation testing for Admin UI decision logic.
//
// Coverage says a line ran; a surviving mutant says no test would notice if that line were wrong.
// Scope is the src/lib decision modules that carry their own coverage floor (ADR-0370 U3), never
// page rendering. CI mutates only the files a PR changes (`--mutate <files>`, see
// scripts/mutation-changed.sh); `npm run test:mutation` mutates the whole scope.
//
// `thresholds.break` is a floor that only goes up (gate admin-ui-mutation-ratchet).

/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
export default {
  testRunner: 'vitest',
  plugins: ['@stryker-mutator/vitest-runner'],
  vitest: { configFile: 'vitest.config.ts', related: true },
  coverageAnalysis: 'perTest',
  mutate: [
    'src/lib/{aml,approvals,auth,closings,consents,delegations,disputes,fees,format,fraud,interest,ledger,lending,mutations,payments,sanctions,settlement}/**/*.ts',
    '!src/lib/**/*.d.ts',
  ],
  // `related: true`: only the tests Vitest relates to the mutated files run, dry run included.
  // Static mutants (module-level initialisers) re-run the whole related suite per mutant: 64 of
  // them took 84% of a 402 s run over src/lib/payments. Ignoring them cut it to 165 s.
  ignoreStatic: true,
  // The floor: measured 2026-11-11 over src/lib/payments (the first module run): 57.0%. Floor below.
  thresholds: { high: 90, low: 75, break: 55 },
  reporters: ['clear-text', 'progress', 'json'],
  jsonReporter: { fileName: 'build/mutation/mutation.json' },
  // Tests read sibling repo trees (../../openbank-*), which a sandbox copy cannot reach.
  // inPlace mutates the checkout and restores every file when the run ends.
  inPlace: true,
  tempDirName: '.stryker-tmp',
  concurrency: 4,
  timeoutMS: 20000,
}
