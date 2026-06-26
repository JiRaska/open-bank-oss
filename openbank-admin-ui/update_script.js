const fs = require('fs')

const file = 'src/app/docs/bpmn/page.tsx'
let content = fs.readFileSync(file, 'utf8')

const replacement = `
// BPMN-like process definitions rendered as SVG flow diagrams
const SERVICE_PORTS: Record<string, number> = {
  'account-service': 8100,
  'ledger-service': 8101,
  'transaction-service': 8102,
  'balance-service': 8103,
  'party-service': 8111,
  'pid-service': 8105,
  'kyc-service': 8114,
  'aml-service': 8117,
  'audit-service': 8113,
  'sepa-payment': 8115,
  'domestic-payment': 8116,
  'consent-service': 8106,
  'psd2-service': 8107,
  'sca-service': 8110,
  'notification-service': 8112,
}

type ProcessStep = {
  id: string
  type: string
  x: number
  y: number
  label: string
  lane?: string
  apis?: string[]
  relatedServices?: string[]
}

type ProcessFlow = {
  from: string
  to: string
  label?: string
}

type ProcessDef = {
  id: string
  name: string
  desc: string
  regulation: string
  steps: ProcessStep[]
  flows: ProcessFlow[]
  services: string[]
}

const PROCESSES: ProcessDef[] = [
  {
    id: 'account-opening',
    name: 'Account Opening',
    desc: 'Proces otevření nového bankovního účtu od žádosti po aktivaci',
    regulation: 'CNB Vyhláška 163/2014 + AML 5AMLD',
    steps: [
      { id: 'start',    type: 'start',    x: 60,  y: 100, label: 'Žádost' },
      { id: 'id-check', type: 'task',     x: 180, y: 100, label: 'Ověření identity (KYC)', lane: 'compliance', apis: ['POST /api/v1/kyc/verify'], relatedServices: ['kyc-service', 'party-service'] },
      { id: 'aml',      type: 'task',     x: 340, y: 100, label: 'AML Screening', lane: 'compliance', apis: ['POST /api/v1/aml/screen'], relatedServices: ['aml-service'] },
      { id: 'gw1',      type: 'gateway',  x: 480, y: 100, label: 'Schváleno?' },
      { id: 'reject',   type: 'end-err',  x: 480, y: 220, label: 'Zamítnuto' },
      { id: 'create',   type: 'task',     x: 600, y: 100, label: 'Vytvoření účtu', lane: 'core', apis: ['POST /api/v1/accounts'], relatedServices: ['account-service'] },
      { id: 'iban',     type: 'task',     x: 740, y: 100, label: 'Přidělení IBAN', lane: 'core', apis: ['POST /api/v1/accounts/{id}/iban'], relatedServices: ['account-service'] },
      { id: 'notify',   type: 'task',     x: 880, y: 100, label: 'Notifikace', lane: 'platform', apis: ['POST /api/v1/notifications'], relatedServices: ['notification-service'] },
      { id: 'end',      type: 'end',      x: 980, y: 100, label: 'Aktivní' },
    ],
    flows: [
      { from: 'start', to: 'id-check' },
      { from: 'id-check', to: 'aml' },
      { from: 'aml', to: 'gw1' },
      { from: 'gw1', to: 'reject', label: 'Ne' },
      { from: 'gw1', to: 'create', label: 'Ano' },
      { from: 'create', to: 'iban' },
      { from: 'iban', to: 'notify' },
      { from: 'notify', to: 'end' },
    ],
    services: ['party-service', 'kyc-service', 'aml-service', 'account-service', 'notification-service'],
  },
  {
    id: 'sepa-payment',
    name: 'SEPA Credit Transfer',
    desc: 'Zpracování SEPA platby od iniciace po settlement',
    regulation: 'PSD2 RTS + SEPA CT Rulebook + EBA Guidelines',
    steps: [
      { id: 'start',   type: 'start',   x: 60,  y: 100, label: 'Iniciace' },
      { id: 'sca',     type: 'task',    x: 180, y: 100, label: 'SCA (PSD2)', lane: 'psd2', apis: ['POST /api/v1/sca/challenge'], relatedServices: ['sca-service'] },
      { id: 'consent', type: 'task',    x: 320, y: 100, label: 'Ověření Consent', lane: 'psd2', apis: ['GET /api/v1/consents/{id}/verify'], relatedServices: ['consent-service'] },
      { id: 'aml',     type: 'task',    x: 460, y: 100, label: 'AML Screening', lane: 'compliance', apis: ['POST /api/v1/aml/transaction-screen'], relatedServices: ['aml-service'] },
      { id: 'gw1',     type: 'gateway', x: 580, y: 100, label: 'Prošlo?' },
      { id: 'reject',  type: 'end-err', x: 580, y: 220, label: 'Odmítnuto' },
      { id: 'debit',   type: 'task',    x: 700, y: 100, label: 'Debit účtu', lane: 'core', apis: ['POST /api/v1/accounts/{id}/debit'], relatedServices: ['account-service'] },
      { id: 'submit',  type: 'task',    x: 820, y: 100, label: 'Submit do SEPA', lane: 'payment', apis: ['POST /api/v1/sepa/outward'], relatedServices: ['sepa-payment'] },
      { id: 'audit',   type: 'task',    x: 820, y: 220, label: 'Audit záznam', lane: 'compliance', apis: ['POST /api/v1/audit/log'], relatedServices: ['audit-service'] },
      { id: 'end',     type: 'end',     x: 940, y: 100, label: 'Settled' },
    ],
    flows: [
      { from: 'start', to: 'sca' },
      { from: 'sca', to: 'consent' },
      { from: 'consent', to: 'aml' },
      { from: 'aml', to: 'gw1' },
      { from: 'gw1', to: 'reject', label: 'Ne' },
      { from: 'gw1', to: 'debit', label: 'Ano' },
      { from: 'debit', to: 'submit' },
      { from: 'submit', to: 'audit', label: 'async' },
      { from: 'submit', to: 'end' },
    ],
    services: ['sca-service', 'consent-service', 'aml-service', 'account-service', 'sepa-payment', 'audit-service'],
  },
  {
    id: 'kyc-process',
    name: 'KYC / CDD Process',
    desc: 'Know Your Customer - Customer Due Diligence proces dle EBA AML Guidelines',
    regulation: 'EBA AML Guidelines + 5AMLD Art. 13 + FATF R.10',
    steps: [
      { id: 'start',   type: 'start',   x: 60,  y: 100, label: 'Nový zákazník' },
      { id: 'collect', type: 'task',    x: 180, y: 100, label: 'Sběr dokumentů', lane: 'kyc', apis: ['POST /api/v1/kyc/documents'], relatedServices: ['kyc-service'] },
      { id: 'pep',     type: 'task',    x: 320, y: 100, label: 'PEP Screening', lane: 'compliance', apis: ['POST /api/v1/aml/pep-check'], relatedServices: ['aml-service'] },
      { id: 'gw-pep',  type: 'gateway', x: 440, y: 100, label: 'PEP?' },
      { id: 'edd',     type: 'task',    x: 440, y: 220, label: 'EDD (Enhanced)', lane: 'compliance', apis: ['POST /api/v1/kyc/edd'], relatedServices: ['kyc-service'] },
      { id: 'cdd',     type: 'task',    x: 560, y: 100, label: 'CDD Standard', lane: 'compliance', apis: ['POST /api/v1/kyc/cdd'], relatedServices: ['kyc-service'] },
      { id: 'risk',    type: 'task',    x: 680, y: 100, label: 'Risk Rating', lane: 'compliance', apis: ['POST /api/v1/aml/risk-rating'], relatedServices: ['aml-service'] },
      { id: 'gw2',     type: 'gateway', x: 800, y: 100, label: 'Schváleno?' },
      { id: 'reject',  type: 'end-err', x: 800, y: 220, label: 'Odmítnuto' },
      { id: 'active',  type: 'task',    x: 920, y: 100, label: 'Aktivace Party', lane: 'identity', apis: ['PUT /api/v1/party/{id}/activate'], relatedServices: ['party-service'] },
      { id: 'end',     type: 'end',     x: 1020,y: 100, label: 'Aktivní' },
    ],
    flows: [
      { from: 'start', to: 'collect' },
      { from: 'collect', to: 'pep' },
      { from: 'pep', to: 'gw-pep' },
      { from: 'gw-pep', to: 'edd', label: 'Ano' },
      { from: 'gw-pep', to: 'cdd', label: 'Ne' },
      { from: 'edd', to: 'risk' },
      { from: 'cdd', to: 'risk' },
      { from: 'risk', to: 'gw2' },
      { from: 'gw2', to: 'reject', label: 'Ne' },
      { from: 'gw2', to: 'active', label: 'Ano' },
      { from: 'active', to: 'end' },
    ],
    services: ['kyc-service', 'aml-service', 'party-service'],
  },
  {
    id: 'aml-screening',
    name: 'AML Transaction Screening',
    desc: 'Automatické AML screenování transakcí dle 5AMLD/6AMLD',
    regulation: '5AMLD Art. 18 + 6AMLD + FATF R.10-12 + EBA AML Guidelines',
    steps: [
      { id: 'start',   type: 'start',   x: 60,  y: 100, label: 'Transakce' },
      { id: 'rules',   type: 'task',    x: 180, y: 100, label: 'Rules Engine', lane: 'aml', apis: ['POST /api/v1/aml/rules/evaluate'], relatedServices: ['aml-service'] },
      { id: 'sanction',type: 'task',    x: 320, y: 100, label: 'Sanctions Check', lane: 'aml', apis: ['POST /api/v1/aml/sanctions'], relatedServices: ['aml-service'] },
      { id: 'gw1',     type: 'gateway', x: 440, y: 100, label: 'Hit?' },
      { id: 'fp',      type: 'task',    x: 440, y: 220, label: 'False Positive?', lane: 'aml', apis: ['GET /api/v1/aml/cases/{id}'], relatedServices: ['aml-service'] },
      { id: 'gw-fp',   type: 'gateway', x: 560, y: 220, label: 'FP?' },
      { id: 'block',   type: 'task',    x: 680, y: 220, label: 'Blokace + SAR', lane: 'aml', apis: ['POST /api/v1/aml/sar'], relatedServices: ['aml-service'] },
      { id: 'clear',   type: 'task',    x: 560, y: 100, label: 'Cleared', lane: 'aml', apis: ['PUT /api/v1/aml/cases/{id}/clear'], relatedServices: ['aml-service'] },
      { id: 'mlro',    type: 'task',    x: 800, y: 220, label: 'MLRO Eskalace', lane: 'compliance', apis: ['POST /api/v1/notifications/mlro'], relatedServices: ['notification-service'] },
      { id: 'end-ok',  type: 'end',     x: 680, y: 100, label: 'Schváleno' },
      { id: 'end-sar', type: 'end-err', x: 920, y: 220, label: 'SAR Filed' },
    ],
    flows: [
      { from: 'start', to: 'rules' },
      { from: 'rules', to: 'sanction' },
      { from: 'sanction', to: 'gw1' },
      { from: 'gw1', to: 'fp', label: 'Hit' },
      { from: 'gw1', to: 'clear', label: 'Clear' },
      { from: 'clear', to: 'end-ok' },
      { from: 'fp', to: 'gw-fp' },
      { from: 'gw-fp', to: 'clear', label: 'Ano' },
      { from: 'gw-fp', to: 'block', label: 'Ne' },
      { from: 'block', to: 'mlro' },
      { from: 'mlro', to: 'end-sar' },
    ],
    services: ['aml-service', 'audit-service', 'notification-service'],
  },
]
`
content = content.replace(/\/\/ BPMN-like process definitions rendered as SVG flow diagrams[\s\S]*?\]\n  \},\n\]/, replacement)

fs.writeFileSync(file, content)
