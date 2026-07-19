// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// A moving particle bound to an edge path (SMIL). The parent decides whether to
// mount it at all (flow toggled off / reduced-motion / edge hidden → not rendered).
// Precedent: system/tests/page.tsx uses animateMotion and passes render-smoke.
export function FlowParticle({ pathId, color, dur, begin, r = 2.5 }: {
  pathId: string; color: string; dur: number; begin: number; r?: number
}) {
  return (
    <circle r={r} fill={color} stroke="var(--surface)" strokeWidth={0.5}>
      <animateMotion dur={`${dur}s`} begin={`${begin}s`} repeatCount="indefinite" rotate="auto" calcMode="linear">
        <mpath href={`#${pathId}`} />
      </animateMotion>
    </circle>
  )
}
