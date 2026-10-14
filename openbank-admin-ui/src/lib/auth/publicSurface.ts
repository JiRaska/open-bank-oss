// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/** Public routes that deliberately run without authenticated operator infrastructure. */
export function isPublicSurface(pathname: string): boolean {
  return (
    pathname === '/auth' ||
    pathname.startsWith('/auth/') ||
    pathname === '/privacy' ||
    // Referral (member-get-member) landing links served at /r/<token>. Visitors are
    // not operators and have no session: the page stores the invite token and routes
    // them on to the public sign-in flow, where attribution happens after login.
    // Note: startsWith('/r/') needs the trailing slash so /reporting etc. stay gated.
    pathname.startsWith('/r/')
  )
}
