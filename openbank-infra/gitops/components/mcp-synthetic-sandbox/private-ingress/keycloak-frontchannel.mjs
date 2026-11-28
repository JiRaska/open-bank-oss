// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { readFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:https'

const { FRONTCHANNEL_HOST, FRONTCHANNEL_SERVICE, FRONTCHANNEL_CUSTOMER_REALM, FRONTCHANNEL_STAFF_REALM } = process.env
if (![FRONTCHANNEL_HOST, FRONTCHANNEL_SERVICE, FRONTCHANNEL_CUSTOMER_REALM].every(Boolean)) {
  throw new Error('missing private frontchannel configuration')
}
const allowedHosts = new Set([FRONTCHANNEL_HOST, `${FRONTCHANNEL_SERVICE}:8443`])
const realms = [FRONTCHANNEL_CUSTOMER_REALM, FRONTCHANNEL_STAFF_REALM].filter(Boolean).map((realm) => `/realms/${realm}/`)
const hop = new Set(['connection', 'keep-alive', 'proxy-authorization', 'proxy-authenticate', 'te', 'trailer',
  'transfer-encoding', 'upgrade'])

function clean(headers) {
  const named = new Set(String(headers.connection ?? '').split(',').map((header) => header.trim().toLowerCase()))
  return Object.fromEntries(Object.entries(headers).filter(([header]) => !hop.has(header) && !named.has(header)))
}

createServer({
  cert: readFileSync('/tls/frontchannel/tls.crt'),
  key: readFileSync('/tls/frontchannel/tls.key'),
  minVersion: 'TLSv1.3',
  requestTimeout: 30_000,
}, (req, res) => {
  const host = req.headers.host
  if (!allowedHosts.has(host)) {
    res.writeHead(421).end()
    return
  }

  const path = req.url?.split('?')[0]
  if (!path || /[%\\;]/.test(path) || path.split('/').some((part) => part === '.' || part === '..') ||
      !['GET', 'POST', 'HEAD'].includes(req.method) ||
      (!realms.some((prefix) => path.startsWith(prefix)) && !path.startsWith('/resources/'))) {
    res.writeHead(404).end()
    return
  }

  const headers = clean(req.headers)
  for (const header of Object.keys(headers)) {
    if (header === 'forwarded' || header.startsWith('x-forwarded-') || header.startsWith('x-ssl-') ||
        header.startsWith('ssl-client-') || header.startsWith('x-client-cert')) delete headers[header]
  }
  headers.host = host
  headers['x-forwarded-host'] = host
  headers['x-forwarded-proto'] = 'https'
  headers['x-forwarded-port'] = host === FRONTCHANNEL_HOST ? '443' : '8443'

  const local = request({ hostname: '127.0.0.1', port: 8080, path: req.url, method: req.method, headers }, (reply) => {
    res.writeHead(reply.statusCode, clean(reply.headers))
    reply.pipe(res)
  })
  local.setTimeout(30_000, () => local.destroy())
  local.on('error', () => {
    if (res.headersSent) res.destroy()
    else res.writeHead(502).end()
  })
  req.on('aborted', () => local.destroy())
  res.on('close', () => local.destroy())
  req.pipe(local)
}).listen(8444, '0.0.0.0')
