# Referral odkazy — openbank-customer-edge

Edge předává pole `referralLink` z referral-service, takže klientská aplikace
neskládá URL sama:

- `POST /customer/v1/referrals/invites` ho vrací vedle jednorázového `token`
  (raw token je záměrně viditelný pouze v této odpovědi).
- `GET /customer/v1/referrals/invites` ho uvádí u každé pozvánky, takže
  aplikace může u každé pozvánky zobrazit kopírovatelný odkaz.

Edge zůstává whitelist projekcí: přidání `referralLink` nemění privacy smlouvu —
žádná identita doporučeného, žádný hash tokenu ani idempotenční klíč se k
aplikaci nedostanou.

Přiváděný zákazník, který dorazí přes sdílený odkaz, přistane na veřejné stránce
`/r/<token>` (admin-ui), která token uloží a předá ho dál jako query parametr
`ref`; aplikace ho po přihlášení odešle na
`POST /customer/v1/referrals/attributions`, kde rate-limitovaná cesta naváže
stranu tokenu jako doporučeného.
