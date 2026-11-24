// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { readFileSync } from 'node:fs'
import { request } from 'node:http'
import { createServer } from 'node:https'

const { STAFF_TOKEN_ISSUER_HOST, STAFF_TOKEN_SERVICE_HOST, STAFF_TOKEN_REALM } = process.env
if (![STAFF_TOKEN_ISSUER_HOST, STAFF_TOKEN_SERVICE_HOST, STAFF_TOKEN_REALM].every(Boolean)) {
  throw new Error('missing staff token relay configuration')
}

const tokenPath = `/realms/${STAFF_TOKEN_REALM}/protocol/openid-connect/token`
const clients = new Set(['openbank-keycloak-mcp', 'openbank-mcp-read-issuer'])
const formType = 'application/x-www-form-urlencoded'
const maxFormBytes = 4096

async function relay(req, res) {
  if (req.headers.host !== STAFF_TOKEN_SERVICE_HOST || req.method !== 'POST' || req.url !== tokenPath) {
    res.writeHead(404).end()
    return
  }
  const size = Number(req.headers['content-length'])
  if (req.headers['content-type'] !== formType || req.headers.authorization || req.headers['transfer-encoding'] ||
      !Number.isSafeInteger(size) || size <= 0 || size > maxFormBytes) {
    res.writeHead(400).end()
    return
  }

  const chunks = []
  for await (const chunk of req) chunks.push(chunk)
  const form = new URLSearchParams(Buffer.concat(chunks).toString('utf8'))
  if (form.size !== 3 || !clients.has(form.get('client_id')) ||
      form.get('grant_type') !== 'client_credentials' || !form.get('client_secret')) {
    res.writeHead(400).end()
    return
  }

  const body = form.toString()
  const keycloak = request({
    hostname: '127.0.0.1', port: 8080, method: 'POST', path: tokenPath,
    headers: {
      host: STAFF_TOKEN_ISSUER_HOST,
      'x-forwarded-host': STAFF_TOKEN_ISSUER_HOST,
      'x-forwarded-port': '443',
      'x-forwarded-proto': 'https',
      'content-type': formType,
      'content-length': Buffer.byteLength(body),
    },
  }, (reply) => {
    if (reply.statusCode >= 300 && reply.statusCode < 400) {
      reply.resume()
      res.writeHead(502).end()
      return
    }
    res.writeHead(reply.statusCode, {
      'cache-control': 'no-store', pragma: 'no-cache',
      'content-type': reply.headers['content-type'] ?? 'application/json',
    })
    reply.on('error', () => res.destroy())
    reply.pipe(res)
  })
  keycloak.setTimeout(5_000, () => keycloak.destroy())
  keycloak.on('error', () => {
    if (res.headersSent) res.destroy()
    else res.writeHead(502).end()
  })
  res.on('close', () => keycloak.destroy())
  keycloak.end(body)
}

createServer({
  cert: readFileSync('/tls/staff-token/tls.crt'),
  key: readFileSync('/tls/staff-token/tls.key'),
  minVersion: 'TLSv1.3',
  requestTimeout: 10_000,
}, (req, res) => {
  relay(req, res).catch(() => {
    if (res.headersSent) res.destroy()
    else res.writeHead(400).end()
  })
}).listen(8445, '0.0.0.0')
