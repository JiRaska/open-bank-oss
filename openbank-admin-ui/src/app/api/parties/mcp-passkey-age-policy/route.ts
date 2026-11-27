// SPDX-License-Identifier: Apache-2.0

import { relayMcpPasskeyAgePolicy } from '@/lib/parties/mcp-passkey-age-policy-server'

export async function GET(req: Request) {
  return relayMcpPasskeyAgePolicy(req, 'GET')
}

export async function PUT(req: Request) {
  return relayMcpPasskeyAgePolicy(req, 'PUT')
}

export async function DELETE(req: Request) {
  return relayMcpPasskeyAgePolicy(req, 'DELETE')
}
