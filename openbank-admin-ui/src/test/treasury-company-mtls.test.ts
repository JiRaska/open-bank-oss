// SPDX-License-Identifier: Apache-2.0
// @vitest-environment node
import { afterAll, beforeAll, expect, it, vi } from 'vitest'
import * as fs from 'node:fs'
import * as https from 'node:https'
import { execFileSync } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { once } from 'node:events'
import { gzipSync, brotliCompressSync, deflateSync } from 'node:zlib'

const fixture = vi.hoisted(() => ({ directory: '', port: 0, missing: false, reads: 0, wrongCa: false }))
vi.mock('node:fs', async importOriginal => {
  const original = await importOriginal<typeof fs>()
  return { ...original, readFileSync: (path: fs.PathOrFileDescriptor, ...args: unknown[]) => {
    if (typeof path === 'string' && path.startsWith('/mnt/treasury-tls/')) {
      fixture.reads++
      if (fixture.missing) throw new Error('missing')
      const name = path.endsWith('ca.crt') && fixture.wrongCa ? 'wrong-ca.crt' : path.split('/').at(-1)!
      return original.readFileSync(join(fixture.directory, name))
    }
    return (original.readFileSync as Function)(path, ...args)
  } }
})
vi.mock('node:https', async importOriginal => {
  const original = await importOriginal<typeof https>()
  return { ...original, request: (url: URL, options: https.RequestOptions, callback: Function) => {
    // Test-only dial override retains fixed TLS servername and production verification options.
    return original.request({ ...options, hostname: '127.0.0.1', port: fixture.port,
      path: url.pathname + url.search, servername: url.hostname }, callback as (res: import('node:http').IncomingMessage) => void)
  } }
})
import { fetchCompanyTreasury, discoveredBaseUrl } from '@/lib/treasury-company'
let server: https.Server
const target = 'https://treasury-service.treasury-pension-co.svc:8443/api/example?x=1'
const call = () => fetchCompanyTreasury(target, { method: 'POST', headers: new Headers({ authorization: 'Bearer test-token', 'idempotency-key': 'retry-1' }), body: new TextEncoder().encode('payload').buffer, signal: new AbortController().signal })
function openssl(...args: string[]) { execFileSync('openssl', args, { cwd: fixture.directory, stdio: 'ignore' }) }
beforeAll(async () => {
  fixture.directory = fs.mkdtempSync(join(tmpdir(), 'ob-treasury-mtls-'))
  openssl('req','-x509','-newkey','rsa:2048','-nodes','-keyout','ca.key','-out','ca.crt','-days','1','-subj','/CN=ephemeral-test-ca')
  openssl('req','-x509','-newkey','rsa:2048','-nodes','-keyout','wrong-ca.key','-out','wrong-ca.crt','-days','1','-subj','/CN=untrusted-test-ca')
  fs.writeFileSync(join(fixture.directory, 'server.ext'), 'subjectAltName=DNS:treasury-service.treasury-pension-co.svc\nextendedKeyUsage=serverAuth\n')
  fs.writeFileSync(join(fixture.directory, 'client.ext'), 'extendedKeyUsage=clientAuth\n')
  for (const name of ['server', 'tls']) {
    openssl('req','-new','-newkey','rsa:2048','-nodes','-keyout',`${name}.key`,'-out',`${name}.csr`,'-subj',`/CN=${name}`)
    openssl('x509','-req','-in',`${name}.csr`,'-CA','ca.crt','-CAkey','ca.key','-CAcreateserial','-out',`${name}.crt`,'-days','1','-extfile',name === 'server' ? 'server.ext' : 'client.ext')
  }
  server = https.createServer({ key: fs.readFileSync(join(fixture.directory,'server.key')), cert: fs.readFileSync(join(fixture.directory,'server.crt')), ca: fs.readFileSync(join(fixture.directory,'ca.crt')), requestCert: true, rejectUnauthorized: true }, (req,res) => {
    let body = ''; req.on('data', data => { body += data }); req.on('end', () => {
      const encoding = req.url?.split('/compressed/')[1]
      if (encoding) {
        const encode = { gzip: gzipSync, br: brotliCompressSync, deflate: deflateSync }[encoding]
        res.writeHead(200, {'content-type':'application/json', 'content-encoding':encoding})
        res.end(encode!(Buffer.from('{"ok":true}'))); return
      }
      if (req.url === '/no-content') { res.writeHead(204); res.end(); return }
      if (req.url === '/abort') { res.writeHead(200); res.flushHeaders(); return }
      res.writeHead(202, { 'content-type': 'application/json', etag: 'v1' })
      res.end(JSON.stringify({ auth: req.headers.authorization, key: req.headers['idempotency-key'], path: req.url, body }))
    })
  })
  server.listen(0, '127.0.0.1'); await once(server, 'listening')
  fixture.port = (server.address() as import('node:net').AddressInfo).port
}, 20000)
afterAll(async () => { if (server) await new Promise<void>(resolve => server.close(() => resolve())); if (fixture.directory) fs.rmSync(fixture.directory, { recursive: true, force: true }) })
it('uses trusted mutual TLS and preserves bearer, idempotency, body, query, status and headers', async () => {
  const response = await call(); expect(response.status).toBe(202); expect(response.headers.get('etag')).toBe('v1')
  expect(await response.json()).toEqual({ auth:'Bearer test-token',key:'retry-1',path:'/api/example?x=1',body:'payload' })
  const reads = fixture.reads; await call(); expect(fixture.reads - reads).toBe(3)
})
it('the actual listener rejects a client without its certificate', async () => {
  await expect(new Promise<void>((resolve,reject) => {
    const req = https.request(new URL(target), { ca: fs.readFileSync(join(fixture.directory,'ca.crt')) }, () => resolve())
    req.on('error', reject); req.end()
  })).rejects.toThrow()
})
it('rejects untrusted authority without an HTTP fallback', async () => {
  fixture.wrongCa = true
  try { await expect(call()).rejects.toThrow() } finally { fixture.wrongCa = false }
})
it('rejects hostname mismatch with the real TLS verifier', async () => {
  await expect(new Promise<void>((resolve,reject) => {
    const req = https.request(new URL('https://wrong.test:8443/'), { ca:fs.readFileSync(join(fixture.directory,'ca.crt')), cert:fs.readFileSync(join(fixture.directory,'tls.crt')), key:fs.readFileSync(join(fixture.directory,'tls.key')) }, () => resolve())
    req.on('error', reject); req.end()
  })).rejects.toThrow(/Hostname\/IP/)
})
it('fails closed when client identity is unavailable', async () => {
  fixture.missing = true
  try { await expect(call()).rejects.toThrow('client identity unavailable') } finally { fixture.missing = false }
})
it('does not accept caller-selected hosts, plaintext or configured alternate targets', async () => {
  await expect(fetchCompanyTreasury('http://treasury-service.treasury-pension-co.svc:8160/', {method:'GET',headers:new Headers(),signal:new AbortController().signal})).rejects.toThrow('Invalid company treasury target')
  process.env.PENSION_COMPANY_TREASURY_URL = 'https://different.invalid:8443'
  try { expect(() => discoveredBaseUrl('treasury-pension-co', 'treasury-pension-co',8160)).toThrow('Invalid company treasury target') } finally { delete process.env.PENSION_COMPANY_TREASURY_URL }
})

it('decodes compressed responses without forwarding stale encoding or length', async () => {
  for (const encoding of ['gzip','br','deflate']) {
    const response = await fetchCompanyTreasury(`https://treasury-service.treasury-pension-co.svc:8443/compressed/${encoding}`, {method:'GET',headers:new Headers(),signal:new AbortController().signal})
    expect(await response.json()).toEqual({ok:true})
    expect(response.headers.has('content-encoding')).toBe(false)
    expect(response.headers.has('content-length')).toBe(false)
  }
})
it('preserves a bodyless 204 response', async () => {
  const response = await fetchCompanyTreasury('https://treasury-service.treasury-pension-co.svc:8443/no-content', {method:'GET',headers:new Headers(),signal:new AbortController().signal})
  expect(response.status).toBe(204); expect(await response.text()).toBe('')
})
it('aborts a response that never completes rather than hanging', async () => {
  const controller = new AbortController()
  const promise = fetchCompanyTreasury('https://treasury-service.treasury-pension-co.svc:8443/abort', {method:'GET',headers:new Headers(),signal:controller.signal})
  const timer = setTimeout(() => controller.abort(), 100)
  try { await expect(promise).rejects.toThrow() } finally { clearTimeout(timer) }
})
