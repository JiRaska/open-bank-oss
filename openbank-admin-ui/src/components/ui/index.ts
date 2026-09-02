// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * Shared UI primitives for admin-ui (ADR-0208 D1).
 *
 * Before this layer existed there was no `components/ui/` at all: 86 pages each hand-rolled their
 * own tables, headings and status colours, so the marginal cost of a page never fell. Import from
 * `@/components/ui` rather than reaching into the individual files.
 *
 * Deliberately small, and every export here has a real caller. A primitive is added when a
 * migrating page needs it, never speculatively — a component invented ahead of its first caller
 * ends up fitting no real page, which is how eight declared-but-unused UI dependency families got
 * into `package.json` in the first place. (A generic Card and a DataTable were written for this
 * tranche and removed again for exactly that reason: nothing consumed them yet.)
 */
export { PageHeader } from './PageHeader'
export { StatCard } from './StatCard'
export { StatusBadge } from './StatusBadge'
export {
  BADGE_CLASS,
  DOT_CLASS,
  SWATCH_CLASS,
  TONE_BORDER_LEFT_CLASS,
  TONE_TEXT_CLASS,
  type Tone,
  statusBadgeClass,
  statusDotClass,
  statusTone,
} from './tone'
