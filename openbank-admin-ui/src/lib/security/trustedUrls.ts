// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

/**
 * Accept only canonical pull-request links for this repository.
 *
 * Agent output crosses a network boundary before it becomes an operator-facing
 * link. Keeping this check shared prevents one BFF from rendering a trusted PR
 * while another accidentally exposes an executable or lookalike URL.
 */
export function trustedRepositoryPullRequestUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const candidate = value.trim()
  if (!candidate) return null

  try {
    const parsed = new URL(candidate)
    const parts = parsed.pathname.split('/')
    return parsed.protocol === 'https:'
      && parsed.hostname === 'github.com'
      && parsed.port === ''
      && parsed.username === ''
      && parsed.password === ''
      && parsed.search === ''
      && parsed.hash === ''
      && parts.length === 5
      && parts[1] === 'JiRaska'
      && parts[2] === 'open-bank-oss'
      && parts[3] === 'pull'
      && /^\d+$/.test(parts[4])
      ? parsed.toString()
      : null
  } catch {
    return null
  }
}

/** Accept a public document destination without executable or credential-bearing schemes. */
export function trustedHttpsUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null
  const candidate = value.trim()
  if (!candidate) return null

  try {
    const parsed = new URL(candidate)
    return parsed.protocol === 'https:'
      && parsed.username === ''
      && parsed.password === ''
      ? parsed.toString()
      : null
  } catch {
    return null
  }
}
