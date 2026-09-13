// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { AuthGuard } from '@/components/auth/AuthGuard'
import { NotificationsContent } from '@/components/notifications/NotificationsPage'

export default function NotificationsPage() {
  return <AuthGuard permission="notifications:view"><NotificationsContent /></AuthGuard>
}
