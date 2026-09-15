// SPDX-License-Identifier: Apache-2.0

/**
 * Returns a canonical absolute HTTPS URL suitable for a browser navigation.
 *
 * External URLs commonly originate in service responses (agent proposals,
 * runbooks, evidence links). Treat them as untrusted at the rendering boundary
 * so an executable or ambiguous scheme can never become a clickable action.
 */
export function safeExternalUrl(value: string | null | undefined): string | null {
  if (!value) return null

  try {
    const url = new URL(value)
    return url.protocol === 'https:' ? url.toString() : null
  } catch {
    return null
  }
}
