// SPDX-License-Identifier: Apache-2.0
// Exact synthetic-record contract for the authenticated, read-only inbox probe.
export function verifyApprovalInboxEvidence(payload, fixture) {
  if (payload?.sources?.billing !== 'ok') throw new Error('billing approval source did not answer successfully')
  const matches = Array.isArray(payload.items)
    ? payload.items.filter(item => item?.domain === 'billing' && item.id === fixture.id)
    : []
  if (matches.length !== 1 || matches[0].action !== fixture.action ||
      matches[0].resourceId !== fixture.resourceId || matches[0].maker !== fixture.makerId ||
      Date.parse(matches[0].proposedAt) !== Date.parse(fixture.createdAt)) {
    throw new Error('sandbox fixture did not traverse billing provider and approval inbox BFF unchanged')
  }
}
