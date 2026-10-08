// SPDX-License-Identifier: Apache-2.0
'use client'

import { useState } from 'react'
import { useLanguage } from '@/lib/i18n/LanguageContext'

/** Reviewed app keys, in the same order as UiMessages.keys and AppCopy.KEYS. */
export const UI_MESSAGE_KEYS = [
  ["status.loading", "Načítání přehledu", "Loading overview"],
  ["status.unavailable", "Výpadek služby", "Service unavailable"],
  ["err.loadAccounts", "Nelze načíst zůstatky", "Balances unavailable"],
  ["err.staleAccounts", "Neaktuální zůstatky", "Stale balances"],
  ["err.session", "Vypršelé přihlášení", "Session expired"],
  ["prod.loading", "Načítání produktů", "Loading products"],
  ["prod.unavailable", "Produkty nedostupné", "Products unavailable"],
  ["prod.retry", "Tlačítko opakování", "Retry button"],
  ["send.processing", "Zpracování platby", "Processing payment"],
  ["send.accepted.sub", "Čekání na dokončení platby", "Awaiting payment completion"],
  ["aml.err.failed", "Uložení se nepovedlo. Zkuste to znovu.", "Saving did not work. Please try again."],
  ["aml.err.invalid", "Banka údaje nepřijala. Zkontrolujte je prosím.", "The bank did not accept the details. Please check them."],
  ["aml.loading", "Načítáme…", "Loading…"],
  ["app.sessionExpired", "Vaše přihlášení vypršelo. Přihlaste se prosím znovu.", "Your session has expired. Please sign in again."],
  ["bot.failAuth", "Přihlášení vypršelo, tak jsem se nedostal k tvým datům. Přihlas se prosím znovu.", "Your session expired, so I couldn't reach your data. Please sign in again."],
  ["bot.failNet", "Nepodařilo se mi spojit se serverem. Zkontroluj připojení.", "I couldn't reach the server. Check your connection."],
  ["bot.failOff", "Asistent je teď nedostupný. Zkus to prosím za chvíli.", "The assistant is unavailable right now. Try again in a moment."],
  ["cards.dead.expired", "Kartě vypršela platnost.", "This card has expired."],
  ["cards.empty.cta", "Vydat kartu", "Issue a card"],
  ["cards.empty.title", "Zatím tu žádná karta není", "No card here yet"],
  ["cards.err.generic", "Nepovedlo se to. Zkus to prosím znovu.", "That didn't work. Please try again."],
  ["cards.err.network", "Tuto kartovou síť účet nepodporuje.", "This account doesn't support that card network."],
  ["cards.err.notFound", "Kartu se nepodařilo najít.", "We couldn't find that card."],
  ["cards.err.productDisabled", "K tomuto účtu karty nepatří.", "This account doesn't come with cards."],
  ["cards.err.quota", "Vyčerpal jsi počet karet pro tento účet.", "You've used up the cards for this account."],
  ["cards.err.reveal", "Údaje se nepodařilo načíst.", "We couldn't load the details."],
  ["cards.err.reveal.network", "Nepodařilo se spojit se serverem. Zkus to prosím znovu.", "Couldn't reach the server. Please try again."],
  ["cards.err.reveal.notLive", "Karta je zablokovaná nebo zrušená, údaje už neukazujeme.", "This card is blocked or closed, so we no longer show its details."],
  ["cards.err.reveal.notStored", "Tahle karta vznikla dřív, než jsme začali čísla ukládat, takže je nemáme z čeho ", "This card was issued before we started storing numbers, so there is nothing to "],
  ["cards.err.reveal.physical", "U fyzické karty najdeš číslo přímo na kartě.", "A physical card's number is printed on the card itself."],
  ["cards.err.sca", "Ověření se nepovedlo. Zkus to prosím znovu.", "Verification failed. Please try again."],
  ["cards.err.scaRejected", "Ověření jsi odmítl.", "You declined the verification."],
  ["cards.state.blocked", "zablokováno", "blocked"],
  ["cards.state.cancelled", "zrušeno", "closed"],
  ["cards.state.expired", "vypršelo", "expired"],
  ["cards.state.pending", "čeká na aktivaci", "not active yet"],
  ["cardtx.pending", "Zatím jen blokace — konečná částka se ještě může změnit (spropitné, čerpací stanice, hotely).", "Still a hold — the final amount can change (tips, fuel pumps, hotels)."],
  ["cardtx.status.booked", "Zaúčtováno", "Booked"],
  ["cardtx.status.pending", "Blokace", "Hold"],
  ["cardtx.status.rejected", "Zamítnuto", "Rejected"],
  ["cmp.submitFailed", "Nepodařilo se odeslat. ", "Couldn't submit. "],
  ["deleg.activity.empty", "Zatím žádná aktivita.", "No activity yet."],
  ["deleg.empty.byMe", "Zatím nikomu nic nesdílíte.", "You are not sharing anything yet."],
  ["deleg.empty.byMe.hint", "Můžete dát někomu blízkému náhled do účtu, aniž byste mu dali své přihlášení.", "You can give someone close a view of an account without giving them your sign-in."],
  ["deleg.empty.withMe", "Nikdo s vámi zatím nic nesdílí.", "Nobody is sharing anything with you yet."],
  ["deleg.err.ELIGIBILITY", "Druhá strana zatím nesplňuje podmínky pro sdílení.", "The other person does not meet the conditions for sharing yet."],
  ["deleg.err.NETWORK", "Nedaří se spojit s bankou.", "We cannot reach the bank."],
  ["deleg.err.NOT_ALLOWED", "Tuhle akci teď provést nelze.", "That action is not possible right now."],
  ["deleg.err.SCA", "Ověření se nepodařilo dokončit. Zkuste to prosím znovu.", "The approval could not be completed. Please try again."],
  ["deleg.err.SERVER", "Něco se nepovedlo. Zkuste to prosím znovu.", "Something went wrong. Please try again."],
  ["deleg.err.UNAUTHORIZED", "Přihlaste se prosím znovu.", "Please sign in again."],
  ["deleg.err.detail", "Technický detail", "Technical detail"],
  ["deleg.err.load", "Sdílení se nepodařilo načíst.", "Sharing could not be loaded."],
  ["deleg.st.DECLINED", "Odmítnuto", "Declined"],
  ["deleg.st.EXPIRED", "Platnost vypršela", "Expired"],
  ["docs.empty", "Zatím tu nic není.", "Nothing here yet."],
  ["docs.loadFailed", "Dokumenty se nepodařilo načíst — zkus to znovu.", "Couldn't load your documents — try again."],
  ["docs.loading", "Načítám dokument…", "Loading document…"],
  ["docsign.error", "Podpis se nezdařil. Zkus to prosím znovu.", "Signing failed. Please try again."],
  ["docsign.loading", "Připravuji dokument…", "Preparing document…"],
  ["err.addCurrency", "Měnu se nepodařilo přidat — zkus to znovu.", "Couldn't add that currency — try again."],
  ["err.exchange", "Směnu se nepodařilo provést — zkus to znovu.", "The exchange didn't go through — try again."],
  ["err.loadApprovals", "Čekající schválení se nepodařilo načíst", "Couldn't load pending approvals"],
  ["err.loadApprovalsBody", "Zkontroluj připojení a zkus to znovu.", "Check your connection and try again."],
  ["err.loadCards", "Karty se nepodařilo načíst — zkus to znovu.", "Couldn't load cards — try again."],
  ["err.loadConsents", "Přístupy se nepodařilo načíst", "Couldn't load third-party access"],
  ["err.loadDisputes", "Reklamace se nepodařilo načíst", "Couldn't load disputes"],
  ["err.loadFees", "Poplatky se nepodařilo načíst", "Couldn't load fees"],
  ["err.loadFeesBody", "Zkontroluj připojení a zkus to znovu.", "Check your connection and try again."],
  ["err.loadInbox", "Zprávy se nepodařilo načíst", "Couldn't load messages"],
  ["err.loadInboxBody", "Zkontroluj připojení a zkus to znovu.", "Check your connection and try again."],
  ["err.loadLoans", "Půjčky se nepodařilo načíst", "Couldn't load loans"],
  ["err.loadLoansBody", "Zkontroluj připojení a zkus to znovu.", "Check your connection and try again."],
  ["err.loadRecall", "Platby se nepodařilo načíst", "Couldn't load payments"],
  ["err.loadRecallBody", "Zkontroluj připojení a zkus to znovu.", "Check your connection and try again."],
  ["err.loadSchedule", "Kalendář se nepodařilo načíst — zkus to znovu.", "Couldn't load the schedule — try again."],
  ["err.loadSdd", "Inkasa se nepodařilo načíst — zkus to znovu.", "Couldn't load direct debits — try again."],
  ["err.loadStanding", "Trvalé příkazy se nepodařilo načíst — zkontroluj připojení.", "Couldn't load standing orders — check your connection."],
  ["err.moveUnsupported", "Převod mezi různými účty a měnami zároveň neumíme — směň měnu na jednom účtu, pak převeď.", "We can't move between two accounts and change currency at once — "],
  ["fb.failed", "Nepodařilo se odeslat. ", "Couldn't send. "],
  ["fx.empty", "Žádné kurzy k zobrazení", "No rates to show"],
  ["fx.emptyHint", "Kurzovní lístek se právě připravuje.", "The rate sheet is being prepared."],
  ["fx.error", "Kurzy se nepodařilo načíst", "Couldn't load rates"],
  ["fx.history.empty", "Žádná historická data", "No historical data"],
  ["fx.history.error", "Historii se nepodařilo načíst", "Couldn't load history"],
  ["fx.trend.empty", "Pro tento pár zatím není dost historických fixingů.", "There are not enough historical fixings for this pair yet."],
  ["fx.trend.error", "Historický trend teď nelze načíst.", "Historical trend is unavailable right now."],
  ["fx.trend.loading", "Načítám tříměsíční trend…", "Loading the three-month trend…"],
  ["gdpr.submitFailed", "Nepodařilo se odeslat. ", "Couldn't submit. "],
  ["health.failed", "Přehled se teď nepodařilo načíst.", "We could not load this just now."],
  ["home.acctLoadFailed", "Účty se nepodařilo načíst. Registrace nebyla dokončena.", "Couldn't load your accounts. Registration wasn't completed."],
  ["home.txEmpty", "Zatím žádné pohyby", "No activity yet"],
  ["home.txEmptyHint", "Tvoje platby se zobrazí tady.", "Your payments will show up here."],
  ["loan.empty", "Žádné půjčky", "No loans"],
  ["loan.emptyBody", "Aktuálně nemáte žádné aktivní půjčky.", "You have no active loans."],
  ["loanApply.failed", "Žádost se nepodařilo odeslat: ", "Could not submit the application: "],
  ["loanApply.status", "Stav: ", "Status: "],
  ["msig.detail.expiredNote", "Lhůta vypršela. Nic se neprovedlo — pokud je to pořád potřeba, zadej to znovu.", "The time ran out. Nothing was carried out — enter it again if it's still needed."],
  ["msig.detail.rejected", "zamítl(a)", "rejected"],
  ["msig.empty.done", "Zatím nic hotového", "Nothing finished yet"],
  ["msig.empty.doneBody", "Podepsané, zamítnuté a vypršelé položky zůstanou tady.", "Signed, rejected and expired items stay here."],
  ["msig.empty.toSign", "Nic nečeká na tvůj podpis", "Nothing is waiting for your signature"],
  ["msig.empty.waiting", "Nic nečeká na ostatní", "Nothing is waiting on others"],
  ["msig.empty.waitingBody", "Platby, které zadáš a které potřebují další podpis, uvidíš tady.", "Payments you enter that need another signature show up here."],
  ["msig.err.conflict", "Mezitím se to změnilo — načetl jsem aktuální stav.", "This changed in the meantime — I've loaded the current state."],
  ["msig.err.enroll", "Tenhle telefon není připravený k podpisu. Zkus to za chvíli znovu.", "This phone isn't ready to sign yet. Try again in a moment."],
  ["msig.err.forbidden", "Za tuhle firmu teď podepisovat nemůžeš", "You can't sign for this company right now"],
  ["msig.err.forbiddenBody", "Podle rejstříku tu nemáš aktivní oprávnění. Kdyby to nesedělo, ozvi se nám.", "The register shows no active authority for you here. If that's wrong, get in touch."],
  ["msig.err.forbiddenSign", "Tuhle položku podepisovat nemůžeš.", "You can't sign this item."],
  ["msig.err.load", "Seznam se nepodařilo načíst", "Couldn't load the list"],
  ["msig.err.loadBody", "Zkontroluj připojení a zkus to znovu. Nic se mezitím neodeslalo.", "Check your connection and try again. Nothing was sent in the meantime."],
  ["msig.err.notFound", "Tahle položka už neexistuje, nebo patří jiné firmě.", "This item no longer exists, or it belongs to another company."],
  ["msig.err.scaDeclined", "Ověření jsi zrušil(a). Nic se nepodepsalo.", "You cancelled the check. Nothing was signed."],
  ["msig.err.sign", "Podpis se nepodařil", "Signing didn't work"],
  ["msig.expired", "vypršelo", "expired"],
  ["msig.payees.empty", "Zatím žádné důvěryhodné účty", "No trusted accounts yet"],
  ["msig.payees.err.conflict", "Tenhle účet už mezi důvěryhodnými je, nebo na podpis čeká.", "This account is already trusted, or waiting for signatures."],
  ["msig.payees.err.iban", "Tohle IBAN nesedí — zkontroluj číslice.", "That IBAN doesn't check out — look at the digits."],
  ["msig.payees.err.name", "Doplň název, ať ostatní vědí, komu podepisují.", "Add a name so the others know who they're signing for."],
  ["msig.payees.err.save", "Nepodařilo se to odeslat. Nic se nezměnilo.", "Couldn't send it. Nothing changed."],
  ["msig.payees.pendingAdd", "Čeká na podpis", "Waiting for signatures"],
  ["msig.payees.pendingRemove", "Odebrání čeká na podpis", "Removal waiting for signatures"],
  ["msig.policy.err.conflict", "Jedna změna pravidel už čeká na podpis. Nejdřív ji dokončete, nebo zamítněte.", "A rules change is already waiting for signatures. Finish or reject it first."],
  ["msig.policy.err.invalid", "Tahle pravidla nedávají smysl — zkontroluj pásma a počty podpisů.", "These rules don't add up — check the bands and signature counts."],
  ["msig.policy.err.threshold", "Zadej hranici větší než nula.", "Enter a threshold above zero."],
  ["msig.policy.pending", "Změna čeká na podpis", "A change is waiting for signatures"],
  ["msig.policy.pendingOpen", "Zobrazit", "Show"],
  ["msig.status.APPROVED", "Podepsáno", "Signed"],
  ["msig.status.AWAITING_INITIATOR", "Nedokončeno — potvrď", "Unfinished — confirm"],
  ["msig.status.EXPIRED", "Vypršelo — nic se neodeslalo", "Expired — nothing was sent"],
  ["msig.status.PENDING", "Čeká na podpis", "Waiting for signatures"],
  ["msig.status.REJECTED", "Zamítnuto", "Rejected"],
  ["msig.status.RELEASED", "Podepsáno a odesláno", "Signed and sent"],
  ["msig.status.RELEASE_FAILED", "Podepsáno, ale banka platbu neprovedla", "Signed, but the bank didn't carry it out"],
  ["msig.status.UNKNOWN", "Stav neznámý", "Status unknown"],
  ["pay.accepted", "Příkaz přijat ke zpracování", "Order accepted for processing"],
  ["pay.noOwnTarget", "Nemáte jiný účet, na který převést.", "You have no other account to transfer to."],
  ["pay.processing", "Zpracovávám…", "Processing…"],
  ["pay.scheduled", "Naplánováno", "Scheduled"],
  ["pay.standingFail", "Trvalý příkaz se nepodařilo založit — nastavte jej v Platbách.", "Could not create the standing order — set it up in Payments."],
  ["pay.unknown.title", "Výsledek platby zatím neznám.", "I can’t confirm the payment yet."],
  ["prod.empty", "Teď pro tebe nemáme žádný produkt k založení.", "There is nothing for you to open right now."],
  ["rcl.empty", "Žádné okamžité platby", "No instant payments"],
  ["rcl.emptyBody", "Odeslané okamžité platby se objeví tady.", "Your sent instant payments appear here."],
  ["rcl.failed", "Odvolání se nepodařilo odeslat — zkus to prosím hned znovu.", "We couldn't send the recall — please try again right away."],
  ["sdd.empty", "Zatím žádná inkasa. Nové povolíte tlačítkem výše.", "No direct debits yet. Authorise one above."],
  ["sdd.statusActive", "Aktivní", "Active"],
  ["sdd.statusCancelled", "Zrušeno", "Cancelled"],
  ["sdd.statusExpired", "Vypršelo", "Expired"],
  ["sdd.statusPending", "Čeká na potvrzení", "Pending"],
  ["sdd.statusSuspended", "Pozastaveno", "Suspended"],
  ["send.accepted", "Příkaz přijat ke zpracování", "Order accepted for processing"],
  ["send.error", "Platba se nezdařila", "Payment failed"],
  ["send.profileChanged", "Změnili jste profil. Zkontrolujte platbu a potvrďte ji znovu.", "Your profile changed. Review and confirm the payment again."],
  ["send.rejected", "Schválení bylo zamítnuto", "Approval was declined"],
  ["so.cancelFailed", "Zrušení příkazu se nepodařilo — zkuste to prosím znovu.", "Cancelling the order failed — please try again."],
  ["so.err.create", "Příkaz se nepodařilo uložit — zkus to znovu.", "We couldn't save the order — try again."],
  ["so.err.profileChanged", "Změnili jste profil. Zkontrolujte příkaz a potvrďte ho znovu.", "Your profile changed. Review and confirm the order again."],
  ["status.unknown", "Aktuální stav neznáme", "Current status is unknown"],
  ["stmt.empty", "Zatím žádné uzavřené výpisy", "No closed statements yet"],
  ["stmt.emptyHint", "Výpis vznikne po uzavření účetního období.", "A statement is minted when an accounting period closes."],
  ["stmt.error", "Výpisy se nepodařilo načíst", "Couldn't load statements"],
  ["tx.pending", "čeká na zaúčtování", "awaiting settlement"],
  ["tx.rejected", "zamítnuto", "rejected"],
  ["txs.empty", "Zatím žádné transakce.", "No transactions yet."],
  ["txs.loadingMore", "Načítám starší…", "Loading older…"],
 ] as const

function groupOf(key: string): string {
  if (/^(app\.session|bot\.failAuth|status|err\.session)/.test(key)) return 'session'
  if (/^(home|prod|err\.(loadAccounts|staleAccounts|addCurrency))/.test(key)) return 'accounts'
  if (/^(send|pay|so|rcl|err\.(exchange|move|loadStanding|loadSchedule|loadRecall))/.test(key)) return 'payments'
  if (/^(cards|cardtx|err\.loadCards)/.test(key)) return 'cards'
  if (/^(loan|sdd|deleg|err\.(loadLoans|loadSdd))/.test(key)) return 'sharing'
  if (/^(fx|msig|err\.loadApprovals)/.test(key)) return 'approvals'
  return 'other'
}

const GROUP_LABELS: Record<string, [string, string]> = {
  session: ['Přihlášení a stav aplikace', 'Sign in and app status'],
  accounts: ['Účty a produkty', 'Accounts and products'],
  payments: ['Platby a převody', 'Payments and transfers'],
  cards: ['Karty', 'Cards'],
  sharing: ['Úvěry, inkasa a sdílení', 'Loans, direct debits and sharing'],
  approvals: ['Kurzy a schvalování', 'Rates and approvals'],
  other: ['Další zprávy', 'Other messages'],
}

export function UiMessageEditor({ value, onChange, disabled }: {
  value: Record<string, string>
  onChange: (value: Record<string, string>) => void
  disabled: boolean
}) {
  const { t } = useLanguage()
  const [locale, setLocale] = useState('cs')
  const [query, setQuery] = useState('')
  const [selectedGroup, setSelectedGroup] = useState('session')
  const visible = UI_MESSAGE_KEYS.filter(([key, cs, en]) =>
    `${key} ${cs} ${en}`.toLocaleLowerCase().includes(query.toLocaleLowerCase()))
  const groups = [...new Set(visible.map(([key]) => groupOf(key)))].filter(group => query || group === selectedGroup)
  return <fieldset disabled={disabled} style={{ border: '1px solid var(--border)', borderRadius: 8, padding: 16 }}>
    <legend>{t('Hlášky lva a lvice v aplikaci', 'Lion and lioness app messages')}</legend>
    <p style={{ color: 'var(--text-secondary)', fontSize: 13 }}>
      {t('Krátce, srozumitelně a empaticky. Při výpadku se omluvíme; při čekání na platbu aktivně hledáme výsledek. Prázdné pole použije výchozí text aplikace. Změny projdou schválením.',
        'Keep it short, clear and empathetic. Apologise during outages; actively check pending payments. Empty fields use the bundled app text. Changes require approval.')}
    </p>
    <label>{t('Jazyk hlášek', 'Message language')}
      <select className="input" value={locale} onChange={e => setLocale(e.target.value)}>
        <option value="cs">Čeština</option><option value="en">English</option>
      </select>
    </label>
    <label style={{ display: 'block', marginTop: 12 }}>
      {t('Hledat hlášku', 'Search messages')}
      <input className="input" type="search" value={query} onChange={e => setQuery(e.target.value)} />
    </label>
    {!query && <nav aria-label={t('Skupiny hlášek', 'Message groups')} style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
      {Object.entries(GROUP_LABELS).map(([group, labels]) => <button key={group} type="button"
        className={group === selectedGroup ? 'btn btn-primary' : 'btn btn-secondary'}
        aria-pressed={group === selectedGroup} onClick={() => setSelectedGroup(group)}>{t(...labels)}</button>)}
    </nav>}
    {groups.map(group => <section key={group}>
      <h3 style={{ marginTop: 20 }}>{t(...GROUP_LABELS[group])}</h3>
      {visible.filter(([key]) => groupOf(key) === group).map(([key, cs, en]) => {
        const fullKey = `${locale}.${key}`
        return <label key={fullKey} style={{ display: 'block', marginTop: 12, fontSize: 13 }}>
          {t(cs, en)} <small style={{ color: 'var(--text-muted)' }}>({key})</small>
          <textarea className="input" rows={2} maxLength={240} value={value[fullKey] ?? ''}
            placeholder={t('Výchozí text aplikace', 'Bundled app text')}
            onChange={e => {
              const next = { ...value }
              if (e.target.value.trim()) next[fullKey] = e.target.value
              else delete next[fullKey]
              onChange(next)
            }} />
          <small style={{ color: 'var(--text-muted)' }}>{(value[fullKey] ?? '').length}/240</small>
          {value[fullKey] && <p aria-label={t('Náhled hlášky', 'Message preview')} style={{ color: 'var(--text-primary)' }}>{value[fullKey]}</p>}
        </label>
      })}
    </section>)}
  </fieldset>
}
