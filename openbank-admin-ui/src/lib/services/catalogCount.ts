// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

/** Runnable components and libraries are modules, but not microservices. */
export function catalogServiceCount(services: readonly { kind: string }[]): number {
  return services.filter(service => service.kind === 'service').length
}
