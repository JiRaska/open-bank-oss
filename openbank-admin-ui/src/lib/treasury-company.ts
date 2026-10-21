// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { readFileSync } from 'node:fs'
import { request } from 'node:https'
import { brotliDecompressSync, gunzipSync, inflateSync } from 'node:zlib'

export const COMPANY_TREASURY_KEY = 'treasury-pension-co'
const BASE_URL = 'https://treasury-service.treasury-pension-co.svc:8443'
export function discoveryServiceKey(name: string, namespace: string): string {
  return name === 'treasury-service' && namespace === COMPANY_TREASURY_KEY ? COMPANY_TREASURY_KEY : name
}
export function discoveredBaseUrl(key: string, namespace: string, port: number): string {
  if (key === COMPANY_TREASURY_KEY) {
    if (namespace !== COMPANY_TREASURY_KEY) throw new Error('Invalid company treasury namespace')
    const configured = process.env.PENSION_COMPANY_TREASURY_URL ?? BASE_URL
    if (configured !== BASE_URL) throw new Error('Invalid company treasury target')
    return configured
  }
  return `http://${key}.${namespace}.svc:${port}`
}

/** One fixed private edge. Reload projected certificate files on every request for rotation. */
export async function fetchCompanyTreasury(url: string, options: {
  method: string; headers: Headers; body?: ArrayBuffer; signal: AbortSignal
}): Promise<Response> {
  const target = new URL(url)
  if (target.origin !== BASE_URL || target.username || target.password) throw new Error('Invalid company treasury target')
  let identity: { ca: Buffer; cert: Buffer; key: Buffer }
  try {
    identity = {
      ca: readFileSync('/mnt/treasury-tls/ca.crt'),
      cert: readFileSync('/mnt/treasury-tls/tls.crt'),
      key: readFileSync('/mnt/treasury-tls/tls.key'),
    }
  } catch { throw new Error('Company treasury client identity unavailable') }
  return new Promise((resolve, reject) => {
    const req = request(target, {
      ...identity, rejectUnauthorized: true,
      method: options.method, headers: Object.fromEntries(options.headers.entries()), signal: options.signal,
    }, res => {
      const chunks: Buffer[] = []
      res.on('error', reject)
      res.on('data', chunk => chunks.push(Buffer.from(chunk)))
      res.on('end', () => {
        try {
          let body = Buffer.concat(chunks)
          const encoding = res.headers['content-encoding']
          if (encoding === 'gzip') body = gunzipSync(body)
          else if (encoding === 'br') body = brotliDecompressSync(body)
          else if (encoding === 'deflate') body = inflateSync(body)
          const headers = new Headers()
          for (const [name, value] of Object.entries(res.headers)) {
            if (value !== undefined && name !== 'content-encoding' && name !== 'content-length') headers.set(name, Array.isArray(value) ? value.join(', ') : value)
          }
          const status = res.statusCode ?? 502
          resolve(new Response([204, 205, 304].includes(status) ? null : new Uint8Array(body), { status, headers }))
        } catch (error) { reject(error) }
      })
    })
    req.on('error', reject)
    if (options.body?.byteLength) req.write(Buffer.from(options.body))
    req.end()
  })
}
