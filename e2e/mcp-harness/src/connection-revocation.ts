// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { RevocationVerification } from './config.ts'
import type { DpopKey } from './dpop.ts'
import { assertScaTarget } from './guard.ts'
import type { JourneyStep } from './journey.ts'
import type { connectMcp } from './mcp.ts'
import { refreshGrant } from './oauth.ts'
import type { AsMetadata, ClientAuth } from './oauth.ts'

const CONNECTION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const REVOKED_REFRESH = 'MCP connection authority did not authorize token issuance'

interface ActiveJourney {
  client: Pick<Awaited<ReturnType<typeof connectMcp>>, 'callTool'>
  accessToken: string
  refreshToken?: string
  dpop: DpopKey
  as: AsMetadata
  resource: string
  auth: ClientAuth
  timeoutMs: number
}

/** Read the connection locator from our issued token. Customer-edge independently proves ownership. */
function connectionFrom(token: unknown): string {
  try {
    if (typeof token === 'string' && token.length <= 8192) {
      const parts = token.split('.')
      if (parts.length === 3) {
        const claim = JSON.parse(Buffer.from(parts[1], 'base64url').toString()).openbank_mcp_connection_id
        if (typeof claim === 'string' && CONNECTION_ID.test(claim)) return claim
      }
    }
  } catch {
    // No token body or parser failure is returned to the caller.
  }
  throw new Error('expected an issued token with a canonical MCP connection ID')
}

/** Run only after the same client successfully read the synthetic account. */
export async function verifyConnectionRevocation(
  cfg: RevocationVerification,
  journey: ActiveJourney,
  step: JourneyStep,
): Promise<void> {
  const { as, resource, auth, dpop, refreshToken } = journey
  const connectionId = connectionFrom(journey.accessToken)
  if (!refreshToken) throw new Error('revocation verification requires the issued refresh token')
  assertScaTarget(cfg.edgeUrl)
  assertScaTarget(as.token_endpoint)
  const rotated = await step('verify refresh before revocation', async () => {
    const result = await refreshGrant(as, { resource, auth, dpop, refreshToken })
    const next = result.json?.refresh_token
    if (result.status !== 200 || result.json?.token_type !== 'DPoP' || typeof next !== 'string' ||
        !next || next === refreshToken || connectionFrom(result.json.access_token) !== connectionId) {
      throw new Error('expected a successful DPoP refresh in the same MCP connection with a rotated token')
    }
    return next
  })

  await step('revoke the synthetic connection in customer-edge', async () => {
    const res = await fetch(`${cfg.edgeUrl}/mcp/connections/${connectionId}/revoke`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${cfg.appToken}` },
      redirect: 'error',
      signal: AbortSignal.timeout(journey.timeoutMs),
    })
    if (res.status !== 204) throw new Error(`customer-edge revocation returned HTTP ${res.status}, expected 204`)
  })

  await step('verify the same MCP token is denied after revocation', async () => {
    const result = await journey.client.callTool({ name: 'get_balance', arguments: { accountId: cfg.accountId } })
    const block = Array.isArray(result.content) && result.content.length === 1 ? result.content[0] : undefined
    if (result.isError !== true || block?.type !== 'text' || block.text !== 'Not permitted') {
      throw new Error('expected explicit MCP grant denial after revocation')
    }
  })

  await step('verify the same refresh family is denied after revocation', async () => {
    const result = await refreshGrant(as, { resource, auth, dpop, refreshToken: rotated })
    if (result.status !== 400 || result.json?.error !== 'invalid_grant' ||
        result.json?.error_description !== REVOKED_REFRESH || 'access_token' in result.json ||
        'refresh_token' in result.json) {
      throw new Error('expected explicit Keycloak MCP connection refusal for the rotated refresh token')
    }
  })
}
