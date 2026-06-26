// SPDX-License-Identifier: MPL-2.0
import { defineConfig } from 'vitest/config'
import path from 'path'

export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // e2e/ is Playwright territory — Vitest only runs src/test/**
    include: ['src/test/**/*.{test,spec}.ts'],
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
