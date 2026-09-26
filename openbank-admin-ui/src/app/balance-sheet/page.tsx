// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { redirect } from 'next/navigation'

// The section has no landing of its own: its first destination is the snapshot list (#10618).
export default function BalanceSheetIndex() {
  redirect('/balance-sheet/snapshots')
}
