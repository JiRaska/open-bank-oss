// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readSettlement } from '@/lib/settlement/upstream'

export const dynamic = 'force-dynamic'
type Context = { params: Promise<{ id: string }> }

export async function GET(_request: Request, { params }: Context) {
  return readSettlement((await params).id)
}
