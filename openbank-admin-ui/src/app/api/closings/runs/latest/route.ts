// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Closings BFF (ADR-0069 D3): the most recent EoM close run. Passes the upstream
// 204 ("the cadence has never run") through verbatim so the UI can show an honest
// empty state instead of an error.

import { forwardToStatementService } from '@/lib/closings/upstream'

export const dynamic = 'force-dynamic'

export async function GET() {
  return forwardToStatementService('/api/v1/statements/close-runs/latest')
}
