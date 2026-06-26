const fs = require('fs');

const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/api/page.tsx';
let content = fs.readFileSync(path, 'utf8');

const newServices = `const SERVICES = [
  { id: 'account',      name: 'Account Service',       port: 8100, group: 'Core Banking',    version: 'v1', desc: 'Account lifecycle, IBAN, authorizations (Core system for bank accounts)' },
  { id: 'ledger',       name: 'Ledger Service',         port: 8101, group: 'Core Banking',    version: 'v1', desc: 'GL accounts, journal entries, double-entry (Financial book of record)' },
  { id: 'transaction',  name: 'Transaction Service',    port: 8102, group: 'Core Banking',    version: 'v1', desc: 'Transaction processing, partitioned storage (Payment execution engine)' },
  { id: 'balance',      name: 'Balance Service',        port: 8103, group: 'Core Banking',    version: 'v1', desc: 'Real-time balances, holds management (Current funds availability)' },
  { id: 'catalog',      name: 'Product Catalog',        port: 8104, group: 'Core Banking',    version: 'v1', desc: 'Banking products, pricing, limits (Product definitions and fees)' },
  { id: 'pid',          name: 'PID Service',            port: 8105, group: 'Identity',        version: 'v1', desc: 'Party identity documents, external IDs (Identity resolution)' },
  { id: 'consent',      name: 'Consent Service',        port: 8106, group: 'PSD2',            version: 'v1', desc: 'PSD2 consent management, AIS/PIS/CBPII (Open Banking user approvals)' },
  { id: 'psd2',         name: 'PSD2 Service',           port: 8107, group: 'PSD2',            version: 'v1', desc: 'Berlin Group NextGenPSD2 API gateway (Regulated API interface)' },
  { id: 'tpp',          name: 'TPP Registry',           port: 8108, group: 'PSD2',            version: 'v1', desc: 'Third Party Provider registry, EBA sync (Verified external consumers)' },
  { id: 'agent',        name: 'Agent Service (MCP)',    port: 8109, group: 'Platform',        version: 'v1', desc: 'AI agent MCP server, OpenBank tools (Intelligent assistant backend)' },
  { id: 'sca',          name: 'SCA Service',            port: 8110, group: 'PSD2',            version: 'v1', desc: 'Strong Customer Authentication, OTP (Multi-factor auth provider)' },
  { id: 'party',        name: 'Party Service',          port: 8111, group: 'Identity',        version: 'v1', desc: 'Customer/company master data, PEP flags (Central customer record)' },
  { id: 'notification', name: 'Notification Service',   port: 8112, group: 'Platform',        version: 'v1', desc: 'Email/SMS/Push notifications (Omnichannel delivery)' },
  { id: 'audit',        name: 'Audit Service',          port: 8113, group: 'Compliance',      version: 'v1', desc: 'Immutable audit trail, EBA ICT Risk (Regulated action logging)' },
  { id: 'kyc',          name: 'KYC Service',            port: 8114, group: 'Compliance',      version: 'v1', desc: 'KYC/CDD/EDD case management (Know Your Customer reviews)' },
  { id: 'sepa',         name: 'SEPA Payment',           port: 8115, group: 'Payments',        version: 'v1', desc: 'SEPA Credit Transfer, SCT Inst (Euro-zone standard payments)' },
  { id: 'domestic',     name: 'Domestic Payment',       port: 8116, group: 'Payments',        version: 'v1', desc: 'Czech domestic payments (Local clearing network)' },
  { id: 'aml',          name: 'AML Service',            port: 8117, group: 'Compliance',      version: 'v1', desc: 'AML screening, sanctions, SAR filing (Anti-Money Laundering engine)' },
  { id: 'card-issuance',name: 'Card Issuance Service',  port: 8118, group: 'Cards',           version: 'v1', desc: 'Card issuance and lifecycle management (Physical and virtual cards)' },
  { id: 'fx',           name: 'FX Service',             port: 8119, group: 'Core Banking',    version: 'v1', desc: 'Foreign exchange rates and conversion (Currency trading engine)' },
  { id: 'security-scanner',name: 'Security Scanner',    port: 8120, group: 'Platform',        version: 'v1', desc: 'Continuous vulnerability scanning (Infrastructure security)' },
  { id: 'standing-order',name:'Standing Order Service', port: 8121, group: 'Payments',        version: 'v1', desc: 'Recurring payments and scheduled transfers (Automated clearing)' },
  { id: 'swift',        name: 'SWIFT Service',          port: 8122, group: 'Payments',        version: 'v1', desc: 'SWIFT MT/MX messaging (International wire transfers)' },
  { id: 'sanctions',    name: 'Sanctions Service',      port: 8123, group: 'Compliance',      version: 'v1', desc: 'Real-time sanctions list screening (Embargo & blocklist checks)' },
  { id: 'clearing',     name: 'Clearing Service',       port: 8124, group: 'Payments',        version: 'v1', desc: 'Interbank clearing and settlement (Payment finality)' },
  { id: 'interest',     name: 'Interest Service',       port: 8125, group: 'Core Banking',    version: 'v1', desc: 'Interest calculation and accrual (Yield and fee engine)' },
  { id: 'dispute',      name: 'Dispute Service',        port: 8126, group: 'Cards',           version: 'v1', desc: 'Card disputes and chargebacks (Fraud recovery process)' },
  { id: 'sepa-instant', name: 'SEPA Instant Service',   port: 8127, group: 'Payments',        version: 'v1', desc: 'Real-time EUR payment processing (SCT Inst clearing)' },
]

const GROUP_COLORS: Record<string, string> = {
  'Core Banking': '#2563eb',
  'Identity':     '#059669',
  'Compliance':   '#dc2626',
  'Payments':     '#7c3aed',
  'PSD2':         '#d97706',
  'Platform':     '#6b7280',
  'Cards':        '#db2777',
}`;

content = content.replace(/const SERVICES = \[\s*\{ id: 'account'[\s\S]*?\}\s*\]\n\nconst GROUP_COLORS: Record<string, string> = \{[\s\S]*?\}/, newServices);

const changelogLink = `
                {/* Changelog link */}
                <a href={\`/docs/changelog/\${svc.id}\`} target="_blank" rel="noreferrer"
                  onClick={e => e.stopPropagation()}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '4px',
                    padding: '5px 10px', fontSize: '11px', fontWeight: 600,
                    background: '#eff6ff', border: '1px solid #bfdbfe',
                    borderRadius: '6px', color: '#2563eb', textDecoration: 'none',
                    flexShrink: 0,
                  }}>
                  <ExternalLink size={11} />
                  Release Notes
                </a>

                {/* Swagger link */}`;

content = content.replace(/{[\s]*\/\* Swagger link \*\/}/, changelogLink);
content = content.replace('dokumentace všech 17 services', 'dokumentace všech 28 services');

fs.writeFileSync(path, content);
console.log('Done replacing api/page.tsx');
