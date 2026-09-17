// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import type { ReactNode } from 'react'

/** Keeps the keyboard focus and horizontal scroll on the same named element. */
export function TableScrollRegion({ label, children }: { label: string; children: ReactNode }) {
  return <div className="table-scroll-region" role="region" aria-label={label} tabIndex={0}>{children}</div>
}
