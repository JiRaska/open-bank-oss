// SPDX-License-Identifier: Apache-2.0

/** Resolve the principal claim used by Quarkus OIDC from a JWT payload. */
export function principalNameFromToken(token: string | null | undefined): string | null {
  if (!token) return null
  const part = token.split('.')[1]
  if (!part) return null
  try {
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '=')
    const claims = JSON.parse(atob(base64)) as Record<string, unknown>
    for (const key of ['upn', 'preferred_username', 'sub']) {
      const value = claims[key]
      if (typeof value === 'string' && value.length > 0) return value
    }
    return null
  } catch {
    return null
  }
}
