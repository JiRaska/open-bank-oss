// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

export interface SingleFlightLock {
  current: boolean
}

export function claimSingleFlight(lock: SingleFlightLock): boolean {
  if (lock.current) return false
  lock.current = true
  return true
}

export function releaseSingleFlight(lock: SingleFlightLock): void {
  lock.current = false
}
