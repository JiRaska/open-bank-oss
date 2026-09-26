// SPDX-License-Identifier: Apache-2.0
import { readSettlement } from '@/lib/settlement/upstream'

export const dynamic = 'force-dynamic'
type Context = { params: Promise<{ id: string }> }

export async function GET(_request: Request, { params }: Context) {
  return readSettlement((await params).id)
}
