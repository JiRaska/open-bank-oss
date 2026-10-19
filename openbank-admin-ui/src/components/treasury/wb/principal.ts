// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Who is at the keyboard, for the four-eyes controls of the workbench. These are courtesies: the
// service refuses a same-person decision (422 FOUR_EYES_VIOLATION) and any AI principal's approval
// or release (403) regardless of what the console shows.
type SessionUser = { roles?: string[]; actorType?: string | null } | null | undefined

/** An AI principal may draft and propose, but never approve, verify or release. */
export function isAiPrincipal(user: SessionUser): boolean {
  if (!user) return false
  return user.actorType === 'AI_AGENT' || (user.roles ?? []).includes('ROLE_AI_AGENT')
}

/** May this person decide (approve / reject / release) an item proposed or created by [proposer]? */
export function mayDecide(proposer: string | null | undefined, actor: string | null, ai: boolean): boolean {
  if (ai) return false
  if (!proposer) return true // unknown proposer: the service's 422 is the control
  return actor === null || actor !== proposer
}
