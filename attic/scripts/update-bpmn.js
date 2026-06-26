const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/bpmn/page.tsx';
let content = fs.readFileSync(path, 'utf8');

// Replace SERVICE_PORTS
const newServicePorts = `const SERVICE_PORTS: Record<string, number> = {
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
  'product-catalog-service': 8104,
  'standing-order-service': 8121,
  'swift-service': 8122,
  'sanctions-service': 8123,
  'clearing-service': 8124,
  'interest-service': 8125,
  'dispute-service': 8126,
  'sepa-instant-service': 8127,
  'security-scanner': 8120,
  'fx-service': 8119,
  'card-issuance-service': 8118,
}`;

content = content.replace(/const SERVICE_PORTS: Record<string, number> = \{[\s\S]*?\}/, newServicePorts);

// Update AML screening process to use sanctions-service
content = content.replace(
  /\{ id: 'sanction',type: 'task',    x: 320, y: 100, label: 'Sanctions Check', lane: 'aml', apis: \['POST \/api\/v1\/aml\/sanctions'\], relatedServices: \['aml-service'\] \}/,
  `{ id: 'sanction',type: 'task',    x: 320, y: 100, label: 'Sanctions Check', lane: 'compliance', apis: ['POST /api/v1/sanctions/screen'], relatedServices: ['sanctions-service'] }`
);

// Add sanctions-service to aml-screening services array
content = content.replace(
  /services: \['aml-service', 'notification-service'\]/,
  `services: ['aml-service', 'sanctions-service', 'notification-service']`
);

// Add Card Issuance process
const cardProcess = `  {
    id: 'card-issuance',
    name: 'Card Issuance',
    desc: 'Proces vydání nové platební karty (fyzické či virtuální)',
    regulation: 'PCI-DSS + PSD2',
    steps: [
      { id: 'start',   type: 'start',   x: 60,  y: 100, label: 'Žádost' },
      { id: 'catalog', type: 'task',    x: 180, y: 100, label: 'Katalog produktů', lane: 'core', apis: ['GET /api/v1/products/{id}'], relatedServices: ['product-catalog-service'] },
      { id: 'gw1',     type: 'gateway', x: 320, y: 100, label: 'Dostupné?' },
      { id: 'reject',  type: 'end-err', x: 320, y: 220, label: 'Zamítnuto' },
      { id: 'issue',   type: 'task',    x: 460, y: 100, label: 'Vytvoření karty', lane: 'cards', apis: ['POST /api/v1/cards'], relatedServices: ['card-issuance-service'] },
      { id: 'notify',  type: 'task',    x: 600, y: 100, label: 'Notifikace PINu', lane: 'platform', apis: ['POST /api/v1/notifications'], relatedServices: ['notification-service'] },
      { id: 'end',     type: 'end',     x: 740, y: 100, label: 'Vydáno' },
    ],
    flows: [
      { from: 'start', to: 'catalog' },
      { from: 'catalog', to: 'gw1' },
      { from: 'gw1', to: 'reject', label: 'Ne' },
      { from: 'gw1', to: 'issue', label: 'Ano' },
      { from: 'issue', to: 'notify' },
      { from: 'notify', to: 'end' },
    ],
    services: ['product-catalog-service', 'card-issuance-service', 'notification-service'],
  },
];`;

content = content.replace(/  \},[\s]*\];/, "  },\n" + cardProcess);

fs.writeFileSync(path, content);
console.log('Done replacing bpmn/page.tsx');
