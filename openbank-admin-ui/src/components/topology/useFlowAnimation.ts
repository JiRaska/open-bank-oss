// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { useState, useEffect, type Dispatch, type SetStateAction } from 'react'

// Flow-animation on/off state for a topology page. Starts ON, but flips OFF once
// on the client if the OS "reduce motion" setting is active (static diagram).
// Seeding to `true` matches the server render, avoiding a hydration mismatch; the
// effect corrects it after mount.
export function useFlowAnimation(): [boolean, Dispatch<SetStateAction<boolean>>] {
  const [flow, setFlow] = useState(true)
  useEffect(() => {
    if (typeof window !== 'undefined' && window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      setFlow(false)
    }
  }, [])
  return [flow, setFlow]
}
