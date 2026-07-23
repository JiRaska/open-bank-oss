# OpenBank Compliance Matrix

> Last updated: 2026-05-26
> Status: **Draft v0.1** — based on regulatory texts in effect as of May 2026.
> This is technical guidance, **not legal advice**. Operators must conduct their own compliance review with qualified legal counsel and the competent authority (CNB for Czech jurisdiction).

## Scope

This document maps regulatory requirements to concrete technical capabilities and OpenBank services. It covers:

- **PSD2** — Payment Services Directive 2 (Directive (EU) 2015/2366) + RTS-SCA (Regulation 2018/389, amended 2022/2360)
- **DORA** — Digital Operational Resilience Act (Regulation (EU) 2022/2554), in effect since 17 Jan 2025
- **PCI DSS v4.0** — Payment Card Industry Data Security Standard, mandatory since 31 Mar 2025
- **GDPR** — General Data Protection Regulation (Regulation (EU) 2016/679)
- **5AMLD** — 5th Anti-Money Laundering Directive (Directive (EU) 2018/843); AMLD6 (Directive (EU) 2024/1640) transposition deadline 10 Jul 2027
- **CNB Act 21/1992** — Czech Banking Act (latest amendment 11 Jan 2026)
- **eIDAS 2.0** — Electronic Identification, Authentication and Trust Services Regulation; EUDI Wallet mandatory by 31 Dec 2026

## Compliance matrix

| Regulation | Article(s) | Requirement | Technical Capability | OpenBank Service | Verification | Priority |
|---|---|---|---|---|---|---|
| **PSD2** | Art. 97(1)(b) | Strong Customer Authentication (SCA) | Multi-factor auth (knowledge + possession + inherence) with cryptographic validation | `sca-service`, `psd2-service` | Penetration test of SCA flow; verify 2 independent factors enforced | **CRITICAL** |
| PSD2 | Art. 97(2) + RTS 2018/389 Art. 5 | Dynamic linking for remote payments | Authentication code dynamically linked to amount + payee; generated before authorization | `sca-service`, `domestic-payment`, `sepa-payment` | Audit logs; verify code changes per amount/payee; test code-reuse prevention | **CRITICAL** |
| PSD2 | RTS Art. 6-9 | SCA element security | Password ≥8 chars + entropy; biometric template protection; device binding with secure key storage | `sca-service` | Code review of auth impl; verify key storage isolation; biometric specs audit | **CRITICAL** |
| PSD2 | Art. 65-67 | AISP/PISP access | API access control; AISP SCA exemption per RTS 2022/2360 conditions; PISP SCA required | `consent-service`, `psd2-service`, `tpp-registry-service` | API audit trail; verify exemption conditions; test PISP SCA enforcement | **HIGH** |
| PSD2 | Art. 96 | Incident reporting | Detection + reporting to CNB within 72h; severity categorization; root-cause analysis | `audit-service` + incident workflow | Incident log review; verify reporting timestamps; alert mechanism tests | **HIGH** |
| PSD2 | RTS Art. 30 | SCA exemptions (low-risk, trusted beneficiaries) | Whitelist mgmt; transaction risk scoring; threshold config (EUR 30 low-risk, EUR 100 trusted) | `domestic-payment`, `sepa-payment`, `aml-service` | Risk scoring audit; whitelist integrity check; test exemption logic | **MEDIUM** |
| **DORA** | Art. 6 | ICT Risk Management Framework | Documented framework: risk tolerance, policies, procedures; annual review + post-incident update | governance (docs/) + `audit-service` | Framework doc review; verify annual review dates; incident-triggered updates | **CRITICAL** |
| DORA | Art. 8(2) | ICT acquisition, development, maintenance | SAST + DAST; security testing for internet-exposed systems; vulnerability remediation plan | CI/CD + `security-scanner` | Code review checklist; SAST/DAST scan results; vuln tracking | **CRITICAL** |
| DORA | Art. 17-20 | Major ICT incident reporting | Detection + reporting to authority within 24h; classification (severity, type); materiality threshold | `audit-service` + incident workflow | Incident register; verify <24h reporting; classification logic tests | **CRITICAL** |
| DORA | Art. 24-27 | Digital Operational Resilience Testing (DORT) | Annual program: scenario testing + advanced testing (TLPT for large entities); documented plan + results | Testing strategy + `security-scanner` | Test plan review; TLPT scope verification; remediation tracking | **HIGH** |
| DORA | Art. 26 | Threat-Led Penetration Testing (TLPT) | Annual TLPT by qualified testers; scope: critical functions, APIs, network segmentation; red-team simulation | External engagement | TLPT report; scope coverage audit; remediation SLA | **HIGH** |
| DORA | Art. 28-30 | Third-party ICT risk management | Vendor assessment (security, resilience, incident reporting); contract clauses for audit rights, data protection, incident notification | Vendor register (docs/) | Vendor risk register; contract audit; SLA verification | **HIGH** |
| DORA | Art. 31 | Critical Third-Party ICT Service Providers (CTPPs) | CTPP identification; direct supervision by competent authority; resilience testing coordination | Governance | CTPP registry; supervision plan | **MEDIUM** |
| DORA | Art. 45 | Information sharing on cyber threats | Threat-intel sharing participation; incident data anonymization; ENISA coordination | `security-scanner` + governance | Threat-intel sharing logs; anonymization verification | **MEDIUM** |
| **PCI DSS v4.0** | Req. 3 | Protect stored cardholder data (PAN) | AES-256 encryption OR tokenization OR hashing (keyed SHA-256) OR truncation (first 6 + last 4) | `card-issuance-service`, `account-service` | Encryption key audit; tokenization mapping verification | **CRITICAL** |
| PCI DSS v4.0 | Req. 3.5-3.7 | Cryptographic key management | Key generation (FIPS 140-3); annual rotation min.; encrypted key storage; split knowledge for manual ops | Vault + `card-issuance-service` | Key inventory audit; rotation log review; key storage isolation | **CRITICAL** |
| PCI DSS v4.0 | Req. 4 | Protect data in transit | TLS 1.2+ (1.3 preferred); cert validation; no self-signed in prod | All services + Kong API gateway | TLS scan (testssl.sh); cert chain validation; cipher suite audit | **CRITICAL** |
| PCI DSS v4.0 | Req. 6.4 | Payment page script security | Script inventory (Subresource Integrity hashes); change detection; WAF rules; client-side monitoring | `openbank-admin-ui` + WAF | Script inventory audit; SRI hash verification; WAF log review | **HIGH** |
| PCI DSS v4.0 | Req. 8 | User authentication | MFA for all CDE access; phishing-resistant (FIDO2, cert-based) recommended; password ≥12 chars | `sca-service`, Keycloak | MFA enforcement audit; phishing-resistant mechanism tests | **CRITICAL** |
| PCI DSS v4.0 | Req. 8.4.2 | MFA mandatory for CDE access | MFA for user, service, application accounts; hardware FIDO2 or software OTP | `sca-service`, Keycloak | MFA log audit; account access review; MFA bypass testing | **CRITICAL** |
| PCI DSS v4.0 | Req. 10 | Logging and monitoring | Audit logs for all CHD access; automated review; retention ≥1 yr (3 months online) | `audit-service` | Log retention verification; automated review rules audit; integrity check | **HIGH** |
| PCI DSS v4.0 | Req. 11 | Testing and scanning | Annual pentest; authenticated internal vuln scans; segmentation testing; payment page change detection | `security-scanner` + external pentest | Pentest report; vuln scan results; segmentation test docs | **HIGH** |
| PCI DSS v4.0 | Req. 12 | Security policy | Documented policy; annual review; employee acknowledgment; IR plan | docs/ + governance | Policy review; acknowledgment records; IR plan audit | **MEDIUM** |
| **GDPR** | Art. 5 | Data protection principles | Lawfulness, transparency, purpose limitation, data minimization, accuracy, integrity, confidentiality, accountability | All services (privacy by design) | Privacy policy audit; data processing audit; consent records | **CRITICAL** |
| GDPR | Art. 25 | Privacy by design and default | DPIA; privacy controls in design; data minimization by default | `party-service`, `kyc-service` | DPIA documentation; system design review; data collection audit | **CRITICAL** |
| GDPR | Art. 30 | Records of processing activities (ROPA) | Documented processing inventory; data flows; retention periods; legal basis; processor agreements | governance + `audit-service` | ROPA review; data flow diagram audit; DPA verification | **HIGH** |
| GDPR | Art. 32 | Security of processing | Encryption (AES-256 at rest, TLS 1.2+ in transit); MFA for admin; access logging; backup tests; IR plan | All services + `audit-service` | Encryption audit; MFA verification; access log review; backup restore test | **CRITICAL** |
| GDPR | Art. 33-34 | Breach notification | Detection + authority notification within 72h; affected individuals without undue delay; breach register | `audit-service` + incident workflow | Breach register review; notification timestamps; authority comms records | **CRITICAL** |
| GDPR | Art. 35 | Data Protection Impact Assessment (DPIA) | DPIA for high-risk processing (large-scale PII, automated decisions); documented mitigation | governance + `party-service` | DPIA docs; risk assessment records; mitigation verification | **HIGH** |
| GDPR | Art. 37 | Data Protection Officer (DPO) | DPO appointment (if required); DPO contact published; DPO independence | Governance (operator's duty) | Appointment letter; contact verification; independence audit | **MEDIUM** |
| **5AMLD** | Art. 13-14 | Customer Due Diligence (CDD) | Identity verification (name, DOB, address); beneficial ownership (≥25% threshold); PEP screening; source-of-funds | `kyc-service`, `aml-service`, `sanctions-service` | KYC doc audit; beneficial ownership register; PEP screening logs | **CRITICAL** |
| 5AMLD | Art. 15 | Enhanced Due Diligence (EDD) | EDD for high-risk customers (PEPs, high-risk jurisdictions, complex structures); additional verification; ongoing monitoring | `kyc-service`, `aml-service` | EDD trigger audit; verification records; monitoring frequency audit | **HIGH** |
| 5AMLD | Art. 18 | Transaction monitoring | Monitoring for suspicious transactions; thresholds: EUR 10 000 (cash), EUR 1 000 (crypto); SAR generation | `aml-service`, `transaction-service` | Monitoring rules audit; SAR register; threshold config verification | **CRITICAL** |
| 5AMLD | Art. 19 | Beneficial ownership register | Central register of beneficial owners; interconnection with other EU member states; access controls | `party-service` + integration with national register | Register audit; interconnection verification; access log review | **HIGH** |
| 5AMLD | Art. 20 | Reporting to FIU | SAR reporting to FIU within 5 working days; no tipping off; reporting documentation | `aml-service`, `audit-service` | SAR register; reporting timestamps; FIU comms records | **HIGH** |
| **CNB Act 21/1992** | § 4 | Banking licence requirements | Min capital CZK 500 000 000 (cash); fit & proper test; business plan; technical prerequisites | Operator's duty | Licence documentation; capital verification; fit & proper records | **CRITICAL** (operator) |
| CNB Act 21/1992 | § 8b | Governance and control system | Governance framework; org structure; internal audit; risk management; compliance function | Governance (docs/) | Framework audit; org chart review; audit plan verification | **HIGH** |
| CNB Act 21/1992 | § 11a-11b | Reporting (FINREP/COREP) | Quarterly/annual XBRL reporting; capital adequacy; liquidity; operational risk; data-quality controls | `audit-service` + `regulatory-service` (in `attic/`) | XBRL file validation; reporting timeline verification; data quality audit | **HIGH** |
| CNB Act 21/1992 | § 12 | Data localization | Personal data of CZ residents processed/stored in CZ or EU; cross-border transfers require CNB approval | Infra deployment decision | Data location audit; transfer approval records | **MEDIUM** |
| CNB Act 21/1992 | § 20d | IT systems and cybersecurity | Adequate IT systems; cybersecurity measures; incident reporting to CNB; BC plan | `audit-service` + infra | IT systems audit; cybersecurity assessment; incident reporting logs; BC/DR plan | **HIGH** |
| **eIDAS 2.0** | Art. 5a | European Digital Identity Wallet (EUDI Wallet) | EUDI Wallet support for KYC/SCA by 31 Dec 2026; selective disclosure of attributes; user-controlled sharing | `kyc-service`, `sca-service`, `account-service` | EUDI Wallet integration test; selective disclosure verification; user consent audit | **HIGH** |
| eIDAS 2.0 | Art. 5b | Wallet authentication mechanisms | Relying-party authentication; wallet-to-RP cert validation; ISO/IEC 18013-5 compliance | `sca-service`, `account-service` | Cert chain audit; ISO 18013-5 compliance verification; auth-flow tests | **HIGH** |
| eIDAS 2.0 | Art. 5c | Wallet onboarding | Remote onboarding at assurance level HIGH; identity proofing; binding personal data to wallet; face capture if required | `kyc-service`, `sca-service` | Onboarding process audit; proofing records; assurance level verification | **MEDIUM** |
| eIDAS 2.0 | Art. 6 | Qualified Electronic Signatures (QES) | QES support for transactions; cert validation; timestamp services | `domestic-payment`, `sepa-payment`, `account-service` | QES validation testing; cert chain audit; timestamp verification | **MEDIUM** |

## Per-service compliance summary

### `sca-service` — Strong Customer Authentication
- PSD2 SCA (Art. 97) + RTS 2018/389 (Art. 4-9): multi-factor, dynamic linking, exemptions
- PCI DSS Req. 8 + 8.4.2: MFA for all CDE access, phishing-resistant
- GDPR Art. 32: MFA for admin, access logging
- eIDAS 2.0 Art. 5a-5b: EUDI Wallet integration

### `domestic-payment`, `sepa-payment`, `sepa-instant`
- PSD2 Art. 97(2) + RTS Art. 5: dynamic linking
- PCI DSS Req. 3-4: PAN encryption (AES-256), TLS 1.2+
- 5AMLD Art. 18: transaction monitoring (EUR 10k cash, EUR 1k crypto thresholds)
- eIDAS 2.0 Art. 6: QES support for high-value transactions

### `account-service`
- PSD2 Art. 65-67: AISP/PISP access control, exemption logic
- PCI DSS Req. 3.5-3.7: key management, encryption at rest
- GDPR Art. 32: encryption, access logging, backup tests
- 5AMLD Art. 13-14: CDD, beneficial ownership, PEP screening
- eIDAS 2.0 Art. 5a: EUDI Wallet attribute sharing

### `audit-service`
- DORA Art. 17-20: incident detection + <24h reporting
- PCI DSS Req. 10: audit logs (≥1 year, 3 months online)
- GDPR Art. 33-34: breach register, 72h notification
- CNB Act § 11a-11b: FINREP/COREP reporting (XBRL)
- 5AMLD Art. 20: SAR reporting to FIU

### `kyc-service`, `aml-service`, `sanctions-service`
- 5AMLD Art. 13-14: CDD, beneficial ownership (≥25%), PEP screening
- 5AMLD Art. 15: EDD for high-risk customers
- 5AMLD Art. 18: transaction monitoring
- 5AMLD Art. 20: SAR reporting
- eIDAS 2.0 Art. 5a-5c: EUDI Wallet integration

### Governance and incident workflow (organizational, not a single service)
- DORA Art. 6: ICT Risk Management Framework (annual review)
- DORA Art. 17-20: <24h incident reporting
- GDPR Art. 25, 30, 35: DPIA, ROPA, privacy by design
- CNB Act § 4, § 8b: licence requirements, governance framework
- PCI DSS Req. 12: security policy, IR plan

### Security testing
- DORA Art. 8, 24-27: source code review, DORT, TLPT
- PCI DSS Req. 11: annual pentest, authenticated scans, segmentation testing
- GDPR Art. 32(1)(d): regular testing, vuln scanning
- DORA Art. 28-30: third-party risk assessment

## Effective dates and deadlines

| Regulation | In force | Full compliance | Notes |
|---|---|---|---|
| PSD2 (2015/2366) | 13 Sept 2019 | 13 Sept 2019 | RTS 2018/389 amended by 2022/2360 (AISP exemption) |
| DORA (2022/2554) | 17 Jan 2025 | 17 Jan 2025 | RTS for TLPT (2025/1190) effective May 2025 |
| PCI DSS v4.0 | 31 Mar 2024 | 31 Mar 2025 | 51 future-dated requirements mandatory since |
| GDPR (2016/679) | 25 May 2018 | 25 May 2018 | Ongoing; no sunset |
| 5AMLD (2018/843) | 26 Jun 2017 | 26 Jun 2017 | AMLD6 (2024/1640) transposition by 10 Jul 2027 |
| CNB Act 21/1992 | 1 Jan 1993 | Ongoing | Czech national law; latest amendment 11 Jan 2026 |
| eIDAS 2.0 (910/2014) | 27 Nov 2014 | 31 Dec 2026 | EUDI Wallet mandatory; implementing regs 2024/2977, 2024/2979, 2024/2982, 2024/2980 |

## Related detailed positions

Deep-dive compliance notes derived from the code on `origin/main`:

- [Instant Payments Regulation (EU 2024/886) compliance position](../compliance/ipr-vop.md) — Verification of Payee, SCT Inst 10-second timing, charge parity, sanctions cadence, and an honest gap list.
- [ISO 20022 message catalog](../compliance/iso-20022-catalog.md) — every pacs/camt/pain and SWIFT MT/MX message type mapped to the service it lives in.

## Sources

- **PSD2**: Directive (EU) 2015/2366; RTS Regulation (EU) 2018/389 (amended by (EU) 2022/2360)
- **DORA**: Regulation (EU) 2022/2554; RTS (EU) 2024/1774, 2025/1190, 2025/532
- **PCI DSS v4.0**: PCI Security Standards Council (March 2022)
- **GDPR**: Regulation (EU) 2016/679
- **5AMLD**: Directive (EU) 2018/843; AMLD6 Directive (EU) 2024/1640
- **CNB Act 21/1992**: Act of the Czech National Council on Banks; latest amendment Jan 2026
- **eIDAS 2.0**: Regulation (EU) 910/2014 + implementing acts 2024/2977, 2024/2979, 2024/2982, 2024/2980

## Disclaimer

This matrix is **technical guidance only**. It does not constitute legal advice. Operators must:
1. Conduct their own legal review with qualified counsel.
2. Engage with the competent authority (e.g., Czech National Bank) for licensing.
3. Re-validate at every major regulatory amendment.
4. Maintain their own organizational/process compliance evidence in addition to technical controls.
