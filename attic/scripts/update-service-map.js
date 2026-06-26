const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/service-map/page.tsx';
let content = fs.readFileSync(path, 'utf8');

const newServicesEdges = `// Service definitions with positions for the map
const SERVICES = [
  // Core Banking
  { id: 'account',     name: 'Account Service',      port: 8100, group: 'core',    x: 125, y: 100,  color: '#2563eb', desc: 'Account lifecycle management, IBAN assignment' },
  { id: 'ledger',      name: 'Ledger Service',       port: 8101, group: 'core',    x: 250, y: 100,  color: '#2563eb', desc: 'Double-entry bookkeeping, GL accounts, journal entries' },
  { id: 'transaction', name: 'Transaction Service',  port: 8102, group: 'core',    x: 375, y: 100,  color: '#2563eb', desc: 'Transaction processing, partitioned by booking_date' },
  { id: 'catalog',     name: 'Product Catalog',      port: 8104, group: 'core',    x: 500, y: 100,  color: '#2563eb', desc: 'Banking products, pricing, limits' },
  { id: 'balance',     name: 'Balance Service',      port: 8103, group: 'core',    x: 125, y: 220,  color: '#2563eb', desc: 'Real-time balance tracking, holds management' },
  { id: 'interest',    name: 'Interest Service',     port: 8125, group: 'core',    x: 250, y: 220,  color: '#2563eb', desc: 'Interest calculation and accrual' },
  { id: 'fx',          name: 'FX Service',           port: 8119, group: 'core',    x: 375, y: 220,  color: '#2563eb', desc: 'Foreign exchange rates and conversion' },
  // Identity
  { id: 'pid',         name: 'PID Service',          port: 8105, group: 'identity', x: 650, y: 100,  color: '#059669', desc: 'Party identity documents, external IDs' },
  { id: 'party',       name: 'Party Service',        port: 8111, group: 'identity', x: 750, y: 100,  color: '#059669', desc: 'Customer/company master data, PEP/sanctions flags' },
  // Platform
  { id: 'agent',       name: 'Agent Service',        port: 8109, group: 'platform', x: 900, y: 100,  color: '#6b7280', desc: 'AI agent MCP server, OpenBank tools for LLMs' },
  { id: 'notification',name: 'Notification Service', port: 8112, group: 'platform', x: 1000,y: 100,  color: '#6b7280', desc: 'Email/SMS/Push notifications, template engine' },
  { id: 'security-scanner',name: 'Security Scanner', port: 8120, group: 'platform', x: 1100,y: 100,  color: '#6b7280', desc: 'Continuous vulnerability scanning' },
  // Cards
  { id: 'card-issuance',name: 'Card Issuance',       port: 8118, group: 'cards',    x: 650, y: 260,  color: '#db2777', desc: 'Card issuance and lifecycle management' },
  { id: 'dispute',     name: 'Dispute Service',      port: 8126, group: 'cards',    x: 750, y: 260,  color: '#db2777', desc: 'Card disputes and chargebacks' },
  // Compliance
  { id: 'audit',       name: 'Audit Service',        port: 8113, group: 'compliance', x: 920, y: 260,  color: '#dc2626', desc: 'Immutable audit trail, EBA ICT Risk compliance' },
  { id: 'kyc',         name: 'KYC Service',          port: 8114, group: 'compliance', x: 1080,y: 260,  color: '#dc2626', desc: 'KYC/CDD/EDD case management, document verification' },
  { id: 'aml',         name: 'AML Service',          port: 8117, group: 'compliance', x: 920, y: 380,  color: '#dc2626', desc: 'AML screening, SAR filing' },
  { id: 'sanctions',   name: 'Sanctions Service',    port: 8123, group: 'compliance', x: 1080,y: 380,  color: '#dc2626', desc: 'Real-time sanctions list screening' },
  // Payments
  { id: 'sepa',        name: 'SEPA Payment',         port: 8115, group: 'payment', x: 125, y: 380,  color: '#7c3aed', desc: 'SEPA Credit Transfer, SCT Inst, SEPA Direct Debit' },
  { id: 'sepa-instant',name: 'SEPA Instant',         port: 8127, group: 'payment', x: 250, y: 380,  color: '#7c3aed', desc: 'Real-time EUR payment processing' },
  { id: 'domestic',    name: 'Domestic Payment',     port: 8116, group: 'payment', x: 375, y: 380,  color: '#7c3aed', desc: 'Czech domestic payments' },
  { id: 'standing-order',name:'Standing Order',      port: 8121, group: 'payment', x: 500, y: 380,  color: '#7c3aed', desc: 'Recurring payments and scheduled transfers' },
  { id: 'swift',       name: 'SWIFT Service',        port: 8122, group: 'payment', x: 125, y: 500,  color: '#7c3aed', desc: 'SWIFT MT/MX messaging' },
  { id: 'clearing',    name: 'Clearing Service',     port: 8124, group: 'payment', x: 250, y: 500,  color: '#7c3aed', desc: 'Interbank clearing and settlement' },
  // PSD2
  { id: 'consent',     name: 'Consent Service',      port: 8106, group: 'psd2',   x: 125, y: 660,  color: '#d97706', desc: 'PSD2 consent management' },
  { id: 'sca',         name: 'SCA Service',          port: 8110, group: 'psd2',   x: 250, y: 660,  color: '#d97706', desc: 'Strong Customer Authentication' },
  { id: 'psd2',        name: 'PSD2 Service',         port: 8107, group: 'psd2',   x: 375, y: 660,  color: '#d97706', desc: 'PSD2 API gateway' },
  { id: 'tpp',         name: 'TPP Registry',         port: 8108, group: 'psd2',   x: 500, y: 660,  color: '#d97706', desc: 'Third Party Provider registry' },
]

// Service dependencies (edges)
const EDGES = [
  // Core flows
  { from: 'transaction', to: 'account',     label: 'validates', type: 'sync' },
  { from: 'transaction', to: 'ledger',      label: 'posts GL',  type: 'sync' },
  { from: 'transaction', to: 'balance',     label: 'updates',   type: 'sync' },
  { from: 'account',     to: 'balance',     label: 'init',      type: 'sync' },
  { from: 'interest',    to: 'account',     label: 'accrues',   type: 'sync' },
  { from: 'fx',          to: 'transaction', label: 'rates',     type: 'sync' },
  // Payments → Core
  { from: 'sepa',        to: 'transaction', label: 'creates',   type: 'sync' },
  { from: 'sepa-instant',to: 'transaction', label: 'creates',   type: 'sync' },
  { from: 'domestic',    to: 'transaction', label: 'creates',   type: 'sync' },
  { from: 'standing-order',to: 'transaction', label: 'creates', type: 'sync' },
  { from: 'swift',       to: 'transaction', label: 'creates',   type: 'sync' },
  { from: 'clearing',    to: 'transaction', label: 'settles',   type: 'sync' },
  { from: 'sepa',        to: 'aml',         label: 'screens',   type: 'sync' },
  { from: 'sepa-instant',to: 'aml',         label: 'screens',   type: 'sync' },
  { from: 'domestic',    to: 'aml',         label: 'screens',   type: 'sync' },
  { from: 'swift',       to: 'aml',         label: 'screens',   type: 'sync' },
  // Identity
  { from: 'account',     to: 'party',       label: 'owner',     type: 'sync' },
  { from: 'kyc',         to: 'party',       label: 'updates',   type: 'sync' },
  { from: 'party',       to: 'pid',         label: 'documents', type: 'sync' },
  // Cards
  { from: 'card-issuance',to: 'account',    label: 'issues',    type: 'sync' },
  { from: 'dispute',     to: 'card-issuance',label: 'blocks',   type: 'sync' },
  // PSD2
  { from: 'psd2',        to: 'consent',     label: 'checks',    type: 'sync' },
  { from: 'psd2',        to: 'sca',         label: 'triggers',  type: 'sync' },
  { from: 'psd2',        to: 'tpp',         label: 'validates', type: 'sync' },
  { from: 'consent',     to: 'account',     label: 'accesses',  type: 'sync' },
  // Compliance
  { from: 'kyc',         to: 'aml',         label: 'triggers',  type: 'async' },
  { from: 'sanctions',   to: 'aml',         label: 'updates',   type: 'async' },
  // Audit (all services → audit via Kafka)
  { from: 'account',     to: 'audit',       label: 'events',    type: 'async' },
  { from: 'transaction', to: 'audit',       label: 'events',    type: 'async' },
  { from: 'party',       to: 'audit',       label: 'events',    type: 'async' },
  { from: 'sepa',        to: 'audit',       label: 'events',    type: 'async' },
  // Notifications
  { from: 'transaction', to: 'notification',label: 'events',    type: 'async' },
  { from: 'kyc',         to: 'notification',label: 'events',    type: 'async' },
  // Agent
  { from: 'agent',       to: 'account',     label: 'MCP tool',  type: 'sync' },
  { from: 'agent',       to: 'transaction', label: 'MCP tool',  type: 'sync' },
  { from: 'agent',       to: 'balance',     label: 'MCP tool',  type: 'sync' },
]

const GROUP_LABELS: Record<string, { label: string; color: string }> = {
  core:       { label: 'Core Banking',    color: '#2563eb' },
  payment:    { label: 'Payments',        color: '#7c3aed' },
  compliance: { label: 'Compliance',      color: '#dc2626' },
  identity:   { label: 'Identity',        color: '#059669' },
  psd2:       { label: 'PSD2 / Open Banking', color: '#d97706' },
  platform:   { label: 'Platform',        color: '#6b7280' },
  cards:      { label: 'Cards',           color: '#db2777' },
}`;

content = content.replace(/\/\/ Service definitions with positions for the map[\s\S]*?const GROUP_LABELS: Record<string, { label: string; color: string }> = \{[\s\S]*?\}/, newServicesEdges);

// Update viewbox and rects
const oldRectsStr = `<svg viewBox="0 0 1000 620" style={{ width: '100%', height: 'auto', display: 'block', background: 'var(--surface)' }}>
            <defs>
              <marker id="arrow-sync" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                <path d="M0,0 L0,6 L8,3 z" fill="#94a3b8" />
              </marker>
              <marker id="arrow-async" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                <path d="M0,0 L0,6 L8,3 z" fill="#c4b5fd" />
              </marker>
            </defs>

            {/* Group backgrounds */}
            <rect x="150" y="40" width="520" height="120" rx="12" fill="#eff6ff" stroke="#bfdbfe" strokeWidth="1" />
            <text x="160" y="58" fontSize="10" fill="#2563eb" fontWeight="700">CORE BANKING</text>

            <rect x="50" y="160" width="200" height="120" rx="12" fill="#f0fdf4" stroke="#bbf7d0" strokeWidth="1" />
            <text x="60" y="178" fontSize="10" fill="#059669" fontWeight="700">IDENTITY</text>

            <rect x="600" y="160" width="360" height="120" rx="12" fill="#fef2f2" stroke="#fecaca" strokeWidth="1" />
            <text x="610" y="178" fontSize="10" fill="#dc2626" fontWeight="700">COMPLIANCE</text>

            <rect x="50" y="300" width="320" height="120" rx="12" fill="#faf5ff" stroke="#e9d5ff" strokeWidth="1" />
            <text x="60" y="318" fontSize="10" fill="#7c3aed" fontWeight="700">PAYMENTS</text>

            <rect x="250" y="440" width="720" height="120" rx="12" fill="#fffbeb" stroke="#fde68a" strokeWidth="1" />
            <text x="260" y="458" fontSize="10" fill="#d97706" fontWeight="700">PSD2 / OPEN BANKING</text>

            <rect x="840" y="40" width="140" height="300" rx="12" fill="#f8fafc" stroke="#e2e8f0" strokeWidth="1" />`;

const newRectsStr = `<svg viewBox="0 0 1200 800" style={{ width: '100%', height: 'auto', display: 'block', background: 'var(--surface)' }}>
            <defs>
              <marker id="arrow-sync" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                <path d="M0,0 L0,6 L8,3 z" fill="#94a3b8" />
              </marker>
              <marker id="arrow-async" markerWidth="8" markerHeight="8" refX="6" refY="3" orient="auto">
                <path d="M0,0 L0,6 L8,3 z" fill="#c4b5fd" />
              </marker>
            </defs>

            {/* Group backgrounds */}
            <rect x="60" y="40" width="500" height="240" rx="12" fill="#eff6ff" stroke="#bfdbfe" strokeWidth="1" />
            <text x="70" y="58" fontSize="10" fill="#2563eb" fontWeight="700">CORE BANKING</text>

            <rect x="600" y="40" width="200" height="120" rx="12" fill="#f0fdf4" stroke="#bbf7d0" strokeWidth="1" />
            <text x="610" y="58" fontSize="10" fill="#059669" fontWeight="700">IDENTITY</text>

            <rect x="840" y="40" width="320" height="120" rx="12" fill="#f8fafc" stroke="#e2e8f0" strokeWidth="1" />
            <text x="850" y="58" fontSize="10" fill="#6b7280" fontWeight="700">PLATFORM</text>

            <rect x="600" y="200" width="200" height="120" rx="12" fill="#fdf2f8" stroke="#fbcfe8" strokeWidth="1" />
            <text x="610" y="218" fontSize="10" fill="#db2777" fontWeight="700">CARDS</text>

            <rect x="840" y="200" width="320" height="240" rx="12" fill="#fef2f2" stroke="#fecaca" strokeWidth="1" />
            <text x="850" y="218" fontSize="10" fill="#dc2626" fontWeight="700">COMPLIANCE</text>

            <rect x="60" y="320" width="500" height="240" rx="12" fill="#faf5ff" stroke="#e9d5ff" strokeWidth="1" />
            <text x="70" y="338" fontSize="10" fill="#7c3aed" fontWeight="700">PAYMENTS</text>

            <rect x="60" y="600" width="500" height="120" rx="12" fill="#fffbeb" stroke="#fde68a" strokeWidth="1" />
            <text x="70" y="618" fontSize="10" fill="#d97706" fontWeight="700">PSD2 / OPEN BANKING</text>`;

content = content.replace(oldRectsStr, newRectsStr);
content = content.replace('17 microservices', '28 microservices');

fs.writeFileSync(path, content);
console.log('Done replacing service-map/page.tsx');
