// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// The browser leg of the connect: Playwright drives the authorization server's login page and a
// Chrome DevTools Protocol WebAuthn VIRTUAL AUTHENTICATOR answers the passkey ceremony, so the
// fresh-passkey requirement (ADR-0385 D2.2) is exercised for real instead of being stubbed.
// The redirect to the loopback URI is intercepted, so no local listener is needed.
import { readFileSync } from 'node:fs'
import type { BrowserContext, Page } from 'playwright-core'
import { chromium } from 'playwright-core'

/** CDP `WebAuthn.addCredential` shape. Base64 (standard) fields, as CDP expects. */
export interface PasskeyCredential {
  credentialId: string
  rpId: string
  privateKey: string
  userHandle: string
  signCount?: number
}

export interface PasskeyBrowser {
  context: BrowserContext
  page: Page
  authenticatorId: string
  close(): Promise<void>
}

export async function openPasskeyBrowser(o: { headless: boolean; passkeyFile?: string }): Promise<PasskeyBrowser> {
  const browser = await chromium.launch({ headless: o.headless, channel: process.env.MCP_HARNESS_BROWSER_CHANNEL || undefined })
  const context = await browser.newContext()
  const page = await context.newPage()
  const cdp = await context.newCDPSession(page)
  await cdp.send('WebAuthn.enable', { enableUI: false })
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', {
    options: {
      protocol: 'ctap2',
      transport: 'internal',
      hasResidentKey: true,
      hasUserVerification: true,
      isUserVerified: true,
      automaticPresenceSimulation: true,
    },
  })
  if (o.passkeyFile) {
    const cred = JSON.parse(readFileSync(o.passkeyFile, 'utf8')) as PasskeyCredential
    await cdp.send('WebAuthn.addCredential', {
      authenticatorId,
      credential: { ...cred, isResidentCredential: true, signCount: cred.signCount ?? 0 },
    })
  }
  return { context, page, authenticatorId, close: () => browser.close() }
}

/**
 * Opens the authorize URL, presses the passkey button (the virtual authenticator answers), and
 * resolves with the full redirect URL the authorization server sent the browser to. `whileWaiting`
 * runs concurrently — it is where the out-of-band SCA approval happens (ADR-0385 D3).
 */
export async function authorizeInBrowser(
  page: Page,
  authorizeUrl: string,
  redirectUri: string,
  o: { timeoutMs: number; whileWaiting?: () => Promise<void> },
): Promise<string> {
  const redirectPrefix = redirectUri.split('?')[0]
  let resolveCb!: (url: string) => void
  const callback = new Promise<string>((r) => { resolveCb = r })
  await page.route((url) => url.toString().startsWith(redirectPrefix), async (route) => {
    resolveCb(route.request().url())
    await route.fulfill({ status: 200, contentType: 'text/plain', body: 'harness: redirect captured' })
  })
  await page.goto(authorizeUrl)
  const selector = process.env.MCP_HARNESS_PASSKEY_SELECTOR ?? '#authenticateWebAuthnButton, button:has-text("passkey")'
  const button = page.locator(selector).first()
  if (await button.isVisible({ timeout: 10_000 }).catch(() => false)) await button.click()
  const timeout = new Promise<never>((_, rej) => setTimeout(() => rej(new Error(`no redirect to ${redirectPrefix} within ${o.timeoutMs} ms (last page: ${page.url()})`)), o.timeoutMs))
  const work = o.whileWaiting ? o.whileWaiting() : Promise.resolve()
  const [url] = await Promise.race([Promise.all([callback, work]), timeout])
  return url
}
