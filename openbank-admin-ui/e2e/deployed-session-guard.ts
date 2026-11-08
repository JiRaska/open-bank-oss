// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The deployed lane's write guard, shared by the sign-in setup and the screen smoke.
import type { Page } from '@playwright/test'

// Same default as playwright.deployed-session.config.ts `baseURL`.
const APP_ORIGIN = new URL(process.env.ADMIN_UI_DEPLOYED_URL ?? 'https://admin.open-bank.tech').origin

// Records what would make the screen a false pass: a write (aborted here) or a failing BFF call.
export function watchTraffic(page: Page) {
  const writes: string[] = []
  const serverErrors: string[] = []
  void page.route('**/*', async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    // Same origin only, so a Server Action POST to a page path counts as well as an /api route.
    // Auth and the browser trace export are the only writes a screen makes by itself; neither
    // reaches a bank backend, and neither does the cross-origin error-telemetry beacon.
    if (url.origin === APP_ORIGIN && req.method() !== 'GET' && req.method() !== 'HEAD'
        && !url.pathname.startsWith('/api/auth/') && url.pathname !== '/api/telemetry/traces') {
      writes.push(`${req.method()} ${url.pathname}`)
      await route.abort()
      return
    }
    await route.continue()
  })
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.origin === APP_ORIGIN && url.pathname.startsWith('/api/') && response.status() >= 500) {
      serverErrors.push(`${response.status()} ${url.pathname}`)
    }
  })
  return { writes, serverErrors }
}
