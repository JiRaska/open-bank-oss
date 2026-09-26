// SPDX-License-Identifier: Apache-2.0

import { MERMAID_CONFIG } from './mermaidConfig'

export type MermaidApi = {
  initialize: (cfg: object) => void
  render: (id: string, src: string) => Promise<{ svg: string }>
}

let mermaidInstance: MermaidApi | null = null
let mermaidRequest: Promise<MermaidApi> | null = null

/** Load and initialize the browser-only renderer once, shared by every documentation surface. */
export async function getMermaid(): Promise<MermaidApi> {
  if (mermaidInstance) return mermaidInstance
  if (mermaidRequest) return mermaidRequest

  mermaidRequest = import('mermaid')
    .then(mod => {
      const instance = (mod.default ?? mod) as unknown as MermaidApi
      instance.initialize(MERMAID_CONFIG)
      mermaidInstance = instance
      return instance
    })
    .catch(error => {
      // A transient chunk failure must be retryable when the operator reopens the lens.
      mermaidRequest = null
      throw error
    })
  return mermaidRequest
}
