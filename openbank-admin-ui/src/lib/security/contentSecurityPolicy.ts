// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

/**
 * Next's development webpack runtime uses evaluated modules for React Refresh. Production never
 * needs that capability: keep `unsafe-eval` tied to the development build mode, while both modes
 * retain the per-request nonce and strict-dynamic trust chain.
 */
export function scriptSourceDirective(nonce: string, development: boolean): string {
  const developmentRuntime = development ? " 'unsafe-eval'" : ''
  return `script-src 'self' 'nonce-${nonce}' 'strict-dynamic'${developmentRuntime}`
}
