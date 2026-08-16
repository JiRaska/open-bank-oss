// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root for details.

export interface AgentModelDescriptor {
  id: string
  sensitivity: string
}

/** A private catalog review is eligible only for an explicitly self-hosted model. */
export function canReviewPrivateCatalogDraft(models: readonly AgentModelDescriptor[]): boolean {
  return models.some(model => model.sensitivity.trim().toUpperCase() === 'SELF_HOSTED')
}
