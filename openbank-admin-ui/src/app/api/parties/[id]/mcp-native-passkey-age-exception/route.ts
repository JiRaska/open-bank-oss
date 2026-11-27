// SPDX-License-Identifier: Apache-2.0

import { relayMcpPasskeyAgePolicy } from '@/lib/parties/mcp-passkey-age-policy-server'

type RouteContext = { params: Promise<{ id: string }> }

export async function GET(req: Request, { params }: RouteContext) {
  return relayMcpPasskeyAgePolicy(req, 'GET', (await params).id)
}

export async function PUT(req: Request, { params }: RouteContext) {
  return relayMcpPasskeyAgePolicy(req, 'PUT', (await params).id)
}

export async function DELETE(req: Request, { params }: RouteContext) {
  return relayMcpPasskeyAgePolicy(req, 'DELETE', (await params).id)
}
