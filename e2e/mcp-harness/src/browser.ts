// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// The browser leg of the connect: Playwright drives the authorization server's login page and a
// Chrome DevTools Protocol WebAuthn VIRTUAL AUTHENTICATOR answers the passkey ceremony, so the
// fresh-passkey requirement (ADR-0385 D2.2) is exercised for real instead of being stubbed.
// The redirect to the loopback URI is intercepted, so no local listener is needed.
import { closeSync, fstatSync, openSync, readFileSync } from 'node:fs'
import type { BrowserContext, Page, Route } from 'playwright-core'
import { chromium } from 'playwright-core'
import { reservePasskeyCounter } from './passkey-counter.ts'
import { assertTarget } from './guard.ts'

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

export async function openPasskeyBrowser(o: { headless: boolean; passkeyFile?: string; minimumSignCount?: number; counterFile?: string }): Promise<PasskeyBrowser> {
  const browser = await chromium.launch({ headless: o.headless, channel: process.env.MCP_HARNESS_BROWSER_CHANNEL || undefined })
  try {
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
      const fd = openSync(o.passkeyFile, 'r')
      let cred: PasskeyCredential
      try {
        const mode = fstatSync(fd)
        if ((o.minimumSignCount !== undefined || o.counterFile) && (!mode.isFile() || (mode.mode & 0o077) !== 0 || mode.size > 16_384)) {
          throw new Error('app sign-in requires a private passkey fixture file')
        }
        try {
          cred = JSON.parse(readFileSync(fd, 'utf8')) as PasskeyCredential
        } catch {
          throw new Error('passkey fixture is not valid JSON')
        }
      } finally { closeSync(fd) }
      const signCount = o.counterFile
        ? reservePasskeyCounter(o.counterFile, cred)
        : Math.max(cred.signCount ?? 0, o.minimumSignCount ?? 0)
      await cdp.send('WebAuthn.addCredential', {
        authenticatorId,
        credential: { ...cred, isResidentCredential: true, signCount },
      })
    }
    return { context, page, authenticatorId, close: () => browser.close() }
  } catch (error) {
    await browser.close()
    throw error
  }
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
  const redirect = new URL(redirectUri)
  let resolveCb!: (url: string) => void
  const callback = new Promise<string>((r) => { resolveCb = r })
  assertTarget(authorizeUrl)
  let refused: Error | undefined
  // Every navigation is guarded, not only the first: a login page must not walk the browser to a
  // host the operator did not allow (src/guard.ts).
  const handleRoute = async (route: Route) => {
    const req = route.request()
    const target = new URL(req.url())
    if (req.isNavigationRequest() && req.frame() === page.mainFrame() &&
        target.origin === redirect.origin && target.pathname === redirect.pathname && !target.username && !target.password) {
      resolveCb(req.url())
      return route.fulfill({ status: 200, contentType: 'text/plain', body: 'harness: redirect captured' })
    }
    if (req.isNavigationRequest()) {
      try { assertTarget(req.url()) } catch (e) { refused = e as Error; return route.abort('blockedbyclient') }
    }
    return route.continue()
  }
  await page.route('**/*', handleRoute)
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    await page.goto(authorizeUrl)
    const selector = process.env.MCP_HARNESS_PASSKEY_SELECTOR ?? '#authenticateWebAuthnButton, button:has-text("passkey")'
    const button = page.locator(selector).first()
    if (await button.isVisible({ timeout: 10_000 }).catch(() => false)) await button.click()
    const timeout = new Promise<never>((_, reject) => {
      timer = setTimeout(() => reject(new Error('no redirect from authorization within the configured timeout')), o.timeoutMs)
    })
    const work = o.whileWaiting ? o.whileWaiting() : Promise.resolve()
    const [url] = await Promise.race([Promise.all([callback, work]), timeout]).catch((e) => { throw refused ?? e })
    if (refused) throw refused
    return url
  } catch (error) {
    throw refused ?? error
  } finally {
    clearTimeout(timer)
    await page.unroute('**/*', handleRoute)
  }
}
