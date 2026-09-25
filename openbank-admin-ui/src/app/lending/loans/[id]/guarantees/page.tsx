// SPDX-License-Identifier: Apache-2.0
import { LendingGuaranteeView } from '@/components/lending/LendingGuaranteeView'

export default async function LoanGuaranteesPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params
  return <LendingGuaranteeView loanId={id} />
}
