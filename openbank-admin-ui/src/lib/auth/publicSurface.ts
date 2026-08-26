// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/** Public routes that deliberately run without authenticated operator infrastructure. */
export function isPublicSurface(pathname: string): boolean {
  return pathname === '/auth' || pathname.startsWith('/auth/') || pathname === '/privacy'
}
