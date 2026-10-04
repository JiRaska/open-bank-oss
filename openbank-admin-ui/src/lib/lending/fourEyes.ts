// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The four-eyes state (FOUR_EYES) is left only by a decision from someone other than the person who
// proposed the application; lending-service refuses a generic advance there with 409
// FOUR_EYES_DECISION_REQUIRED. The server is the authority — this only decides what the screen says.

export const FOUR_EYES_STATE = 'FOUR_EYES'

type SessionUser = { id?: string | null; name?: string | null; email?: string | null } | null | undefined

/** Whether the signed-in user is the application's proposer. The service records the JWT principal
 *  name, which may be the subject, the username or the e-mail depending on the realm, so any match
 *  counts. Unknown on either side is "not shown as the proposer" — the server still refuses. */
export function isProposer(proposedBy: string | null | undefined, user: SessionUser): boolean {
  if (!proposedBy || !user) return false
  const p = proposedBy.trim().toLowerCase()
  return [user.id, user.name, user.email].some((v) => typeof v === 'string' && v.trim().toLowerCase() === p)
}
