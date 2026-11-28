// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
//
// Proves the passkey leg without any authorization server: a local page registers a passkey and
// then asserts it (both answered by the CDP virtual authenticator), and only after a successful
// assertion redirects to the loopback URI with a code. Needs a Chromium: set
// MCP_HARNESS_BROWSER_CHANNEL=chrome, or run `npx playwright-core install chromium`.
// Reported as SKIPPED (never passed) when no browser is configured.
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import type { AddressInfo } from 'node:net'
import { test } from 'node:test'
import { authorizeInBrowser, openPasskeyBrowser } from '../src/browser.ts'

const PAGE = (redirect: string, withPasskey: boolean) => `<!doctype html><html><body>
<button id="authenticateWebAuthnButton">Sign in with passkey</button>
<script>
const rnd = (n) => crypto.getRandomValues(new Uint8Array(n));
document.getElementById('authenticateWebAuthnButton').onclick = async () => {
  ${withPasskey ? `await navigator.credentials.create({ publicKey: { rp: { name: 'harness' }, user: { id: rnd(16), name: 'u', displayName: 'u' },
    challenge: rnd(32), pubKeyCredParams: [{ type: 'public-key', alg: -7 }], authenticatorSelection: { residentKey: 'required', userVerification: 'required' } } });` : ''}
  const a = await navigator.credentials.get({ publicKey: { challenge: rnd(32), userVerification: 'required' } });
  if (a && a.response.signature.byteLength > 0) location.href = ${JSON.stringify(redirect)} + '?code=from-passkey&state=s';
};
</script></body></html>`

const enabled = Boolean(process.env.MCP_HARNESS_BROWSER_CHANNEL || process.env.MCP_HARNESS_BROWSER_TEST)

async function serve(withPasskey: boolean, redirect: string) {
  const srv = createServer((_, res) => { res.writeHead(200, { 'content-type': 'text/html' }); res.end(PAGE(redirect, withPasskey)) })
  await new Promise<void>((ok) => srv.listen(0, '127.0.0.1', ok))
  return { url: `http://localhost:${(srv.address() as AddressInfo).port}/`, close: () => srv.close() }
}

test('virtual authenticator answers registration + assertion and the loopback redirect is captured', { skip: !enabled && 'no browser configured' }, async () => {
  const redirect = 'http://127.0.0.1:53682/callback'
  const s = await serve(true, redirect)
  const b = await openPasskeyBrowser({ headless: true })
  try {
    const cb = await authorizeInBrowser(b.page, s.url, redirect, { timeoutMs: 20_000 })
    assert.equal(new URL(cb).searchParams.get('code'), 'from-passkey')
  } finally { await b.close(); s.close() }
})

test('known-positive: with no passkey on the authenticator, no redirect happens', { skip: !enabled && 'no browser configured' }, async () => {
  const redirect = 'http://127.0.0.1:53682/callback'
  const s = await serve(false, redirect)
  const b = await openPasskeyBrowser({ headless: true })
  try {
    await assert.rejects(authorizeInBrowser(b.page, s.url, redirect, { timeoutMs: 8_000 }), /no redirect/)
  } finally { await b.close(); s.close() }
})

test('a login page that navigates to a non-allowed host is stopped by the target guard', { skip: !enabled && 'no browser configured' }, async () => {
  const srv = createServer((_, res) => { res.writeHead(200, { 'content-type': 'text/html' }); res.end('<script>location.href="https://mcp.examplebank.com/login"</script>') })
  await new Promise<void>((ok) => srv.listen(0, '127.0.0.1', ok))
  const b = await openPasskeyBrowser({ headless: true })
  try {
    await assert.rejects(authorizeInBrowser(b.page, `http://localhost:${(srv.address() as AddressInfo).port}/`, 'http://127.0.0.1:53682/callback', { timeoutMs: 8_000 }), /mcp\.examplebank\.com is not loopback/)
  } finally { await b.close(); srv.close() }
})

test('app HTTPS callback is intercepted locally and the same authenticator can finish the MCP login', { skip: !enabled && 'no browser configured' }, async () => {
  const appRedirect = 'https://open-bank.tech/app/oauth/callback'
  const mcpRedirect = 'http://127.0.0.1:53682/callback'
  // Both local pages share an RP ID. Only the first registers the virtual credential.
  const app = await serve(true, appRedirect)
  const mcp = await serve(false, mcpRedirect)
  const browser = await openPasskeyBrowser({ headless: true })
  try {
    const appCode = await authorizeInBrowser(browser.page, app.url, appRedirect, { timeoutMs: 20_000 })
    const mcpCode = await authorizeInBrowser(browser.page, mcp.url, mcpRedirect, { timeoutMs: 20_000 })
    assert.equal(new URL(appCode).searchParams.get('code'), 'from-passkey')
    assert.equal(new URL(mcpCode).searchParams.get('code'), 'from-passkey')
  } finally { await browser.close(); app.close(); mcp.close() }
})
