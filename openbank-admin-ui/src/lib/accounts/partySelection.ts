export type AccountPartySelection = {
  id: string
  legalName?: string | null
  tradingName?: string | null
}

/** Keep account sanctions screening aligned with the selected party. */
export function accountPartySelection(party: AccountPartySelection) {
  return {
    partyId: party.id,
    legalName: party.legalName?.trim() || party.tradingName?.trim() || '',
  }
}
