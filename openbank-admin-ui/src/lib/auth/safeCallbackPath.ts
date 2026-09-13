// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

const CALLBACK_ORIGIN = "https://openbank.invalid"
const DEFAULT_CALLBACK = "/dashboard"

/** Keeps post-authentication navigation on the admin UI origin. */
export function sameOriginPath(candidate: string | null | undefined): string | null {
  if (!candidate?.startsWith("/") || candidate.startsWith("//") || candidate.includes("\\")) return null
  try {
    const target = new URL(candidate, CALLBACK_ORIGIN)
    if (target.origin !== CALLBACK_ORIGIN) return null
    return `${target.pathname}${target.search}${target.hash}`
  } catch {
    return null
  }
}

/** Keeps post-authentication navigation on the admin UI origin with a safe default. */
export function safeCallbackPath(candidate: string | null | undefined): string {
  return sameOriginPath(candidate) ?? DEFAULT_CALLBACK
}
