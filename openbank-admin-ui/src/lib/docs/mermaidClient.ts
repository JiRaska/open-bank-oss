// SPDX-License-Identifier: Apache-2.0

import { MERMAID_CONFIG } from '@/lib/docs/mermaidConfig'

export type MermaidApi = {
  initialize: (cfg: object) => void
  render: (id: string, src: string) => Promise<{ svg: string }>
}

let instance: MermaidApi | null = null

/** Loads the large browser renderer only after a page proves it contains a visible diagram. */
export async function getMermaid(): Promise<MermaidApi> {
  if (instance) return instance
  const mod = await import('mermaid')
  const mermaid = (mod.default ?? mod) as unknown as MermaidApi
  mermaid.initialize(MERMAID_CONFIG)
  instance = mermaid
  return mermaid
}
