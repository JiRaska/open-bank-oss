// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { X509Certificate } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { createServer, request as httpsRequest } from 'node:https'

const need = (key) => {
  const value = process.env[key]
  if (!value) throw new Error(`missing ${key}`)
  return value
}
const origin = (key) => {
  const url = new URL(need(key))
  if (url.protocol !== 'https:' || url.pathname !== '/' || url.username || url.password || url.search || url.hash) {
    throw new Error(`expected a private HTTPS origin for ${key}`)
  }
  return url
}
const cert = (role, name) => readFileSync(`/tls/${role}/${name}`)
const canonicalHost = need('MCP_ROUTER_HOST')
const customerRealm = need('MCP_ROUTER_CUSTOMER_REALM')
if (!/^[a-z0-9-]+$/.test(customerRealm)) throw new Error('expected a canonical customer realm name')
const realmPrefix = `/realms/${customerRealm}/`
const mcp = origin('MCP_ROUTER_MCP_ORIGIN')
const keycloak = origin('MCP_ROUTER_ISSUER_ORIGIN')
const edge = origin('MCP_ROUTER_CUSTOMER_EDGE_ORIGIN')
const sandboxCa = cert('server', 'ca.crt')
function upstreamCa(role) {
  const file = process.env[`MCP_ROUTER_${role}_CA_FILE`]
  if (!file) return sandboxCa
  const pem = readFileSync(file, 'utf8').trim()
  if (!/^-----BEGIN CERTIFICATE-----\r?\n[A-Za-z0-9+/=\r\n]+-----END CERTIFICATE-----$/.test(pem) ||
      !new X509Certificate(pem).ca) {
    throw new Error(`expected the single public server CA for ${role}`)
  }
  return pem
}
const caByBackend = new Map([[mcp, upstreamCa('MCP')], [edge, upstreamCa('EDGE')], [keycloak, upstreamCa('OIDC')]])
const mcpIdentity = { cert: cert('mcp-client', 'tls.crt'), key: cert('mcp-client', 'tls.key') }
const metadata = new Set(['/.well-known/oauth-protected-resource', '/.well-known/oauth-protected-resource/customer/mcp'])
const edgeReads = [
  /^\/customer\/v1\/mcp\/connect-requests\/[a-f0-9]{64}$/,
  /^\/customer\/v1\/mcp-proposals\/[a-f0-9-]{36}$/,
  /^\/customer\/v1\/sca\/pending$/,
]
const edgeWrites = [
  /^\/customer\/v1\/mcp\/connect-requests\/[a-f0-9]{64}\/selection$/,
  /^\/customer\/v1\/mcp\/connections\/[a-f0-9-]{36}\/revoke$/,
  /^\/customer\/v1\/sca\/challenges\/[a-f0-9-]{36}\/decision$/,
]
const hopByHop = new Set(['connection', 'keep-alive', 'proxy-authorization', 'proxy-authenticate', 'te', 'trailer',
  'transfer-encoding', 'upgrade'])

function withoutHopHeaders(headers) {
  const dynamic = new Set(String(headers.connection ?? '').split(',').map((item) => item.trim().toLowerCase()))
  return Object.fromEntries(Object.entries(headers).filter(([key]) => !hopByHop.has(key) && !dynamic.has(key)))
}

function selectBackend(method, pathname) {
  if ((pathname === '/customer/mcp' && ['POST', 'GET', 'DELETE'].includes(method)) ||
      (metadata.has(pathname) && method === 'GET')) return mcp
  if ((pathname.startsWith(realmPrefix) || pathname.startsWith('/resources/')) &&
      ['GET', 'POST', 'HEAD'].includes(method)) return keycloak
  if (method === 'GET' && edgeReads.some((path) => path.test(pathname))) return edge
  if (method === 'POST' && edgeWrites.some((path) => path.test(pathname))) return edge
}

const server = createServer({
  cert: cert('server', 'tls.crt'),
  key: cert('server', 'tls.key'),
  minVersion: 'TLSv1.3',
  requestTimeout: 30_000,
}, (req, res) => {
  if (req.headers.host !== canonicalHost && req.headers.host !== `${canonicalHost}:443`) {
    res.writeHead(421).end()
    return
  }
  const pathname = req.url?.split('?')[0]
  // Prevent authority/path transformations between this selector and upstream route matching.
  if (!pathname?.startsWith('/') || pathname.startsWith('//') || /[%\\;]/.test(pathname) ||
      pathname.split('/').some((part) => part === '.' || part === '..')) {
    res.writeHead(404).end()
    return
  }
  const backend = selectBackend(req.method, pathname)
  if (!backend) {
    res.writeHead(404).end()
    return
  }
  const headers = withoutHopHeaders(req.headers)
  for (const name of Object.keys(headers)) {
    if (name === 'forwarded' || name.startsWith('x-forwarded-') ||
        name === 'x-real-ip' || name === 'x-envoy-external-address') delete headers[name]
  }
  headers.host = canonicalHost
  if (backend === mcp) headers['x-envoy-external-address'] = req.socket.remoteAddress
  const upstream = httpsRequest({
    hostname: backend.hostname,
    port: backend.port || 443,
    path: req.url,
    method: req.method,
    servername: backend.hostname,
    headers,
    ca: caByBackend.get(backend),
    minVersion: 'TLSv1.3',
    ...(backend === mcp ? mcpIdentity : {}),
  }, (reply) => {
    res.writeHead(reply.statusCode, withoutHopHeaders(reply.headers))
    reply.pipe(res)
  })
  upstream.setTimeout(30_000, () => upstream.destroy())
  upstream.on('error', () => {
    if (res.headersSent) res.destroy()
    else res.writeHead(502).end()
  })
  req.on('aborted', () => upstream.destroy())
  res.on('close', () => upstream.destroy())
  req.pipe(upstream)
})
server.listen(8443, '0.0.0.0')
