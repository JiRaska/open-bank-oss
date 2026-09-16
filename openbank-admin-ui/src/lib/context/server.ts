// SPDX-License-Identifier: Apache-2.0

import { serverSvcUrl } from '@/lib/services/bff'

export function contextServiceUrl(path: string): string {
  const configured = process.env.CONTEXT_SERVICE_URL?.replace(/\/$/, '')
  return configured ? `${configured}${path}` : serverSvcUrl('context-service', 'context', 8149, path)
}

export function contextHeaders(token: string): HeadersInit {
  return { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${token}` }
}
