// SPDX-License-Identifier: Apache-2.0

import { contextServiceUrl } from '@/lib/context/server'
import { parseOwnershipHistory, type OwnershipHistory } from '@/lib/context/kybOwnership'
import { readBoundedContextJson } from '@/lib/context/boundedJson'

export const readBoundedOwnershipJson = readBoundedContextJson

/** Revalidate the exact case assignment on every list and selected-detail request. */
export async function loadAuthorizedOwnershipHistory(caseId: string, token: string): Promise<{ status: number; history?: OwnershipHistory }> {
  const response = await fetch(contextServiceUrl(`/api/v1/context/kyb-cases/${caseId}/ownership-observations`), {
    cache: 'no-store', signal: AbortSignal.timeout(5_000),
    headers: {
      Accept: 'application/json', Authorization: `Bearer ${token}`,
      'X-Investigation-Case-Id': caseId, 'X-Investigation-Purpose': 'KYB_OWNERSHIP_REVIEW',
    },
  })
  if (!response.ok) return { status: [401, 403, 404, 429, 503].includes(response.status) ? response.status : 502 }
  const history = parseOwnershipHistory(await readBoundedOwnershipJson(response, 32 * 1024))
  if (history.root !== `kyb-case:${caseId}`) throw new Error('Scope mismatch')
  return { status: 200, history }
}
