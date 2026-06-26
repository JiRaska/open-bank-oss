const fs = require('fs');
const file = 'src/components/layout/Sidebar.tsx';
let content = fs.readFileSync(file, 'utf8');

content = content.replace(
`const coreNav: NavItem[] = [
  { nameCs: 'Přehled',      nameEn: 'Dashboard',    href: '/dashboard',    icon: LayoutDashboard },
  { nameCs: 'Poplatky',     nameEn: 'Fees',         href: '/fees',         icon: Receipt,         permission: 'payments:view' },
  { nameCs: 'Účty',         nameEn: 'Accounts',     href: '/accounts',     icon: CreditCard,      permission: 'accounts:view' },
  { nameCs: 'Transakce',    nameEn: 'Transactions', href: '/transactions', icon: ArrowLeftRight,  permission: 'transactions:view' },
  { nameCs: 'Hlavní kniha', nameEn: 'Ledger',       href: '/ledger',       icon: BookOpen,        permission: 'accounts:view' },
]`,
`const coreNav: NavItem[] = [
  { nameCs: 'Přehled',      nameEn: 'Dashboard',    href: '/dashboard',    icon: LayoutDashboard },
  { nameCs: 'Účty',         nameEn: 'Accounts',     href: '/accounts',     icon: CreditCard,      permission: 'accounts:view' },
  { nameCs: 'Transakce',    nameEn: 'Transactions', href: '/transactions', icon: ArrowLeftRight,  permission: 'transactions:view' },
  { nameCs: 'Hlavní kniha', nameEn: 'Ledger',       href: '/ledger',       icon: BookOpen,        permission: 'accounts:view' },
]

const revenueNav: NavItem[] = [
  { nameCs: 'Poplatky',     nameEn: 'Fees',         href: '/fees',         icon: Receipt,         permission: 'payments:view' },
]`
);

content = content.replace(
`        <NavSection items={filter(coreNav)} pathname={pathname} />
        <SectionLabel>{t('Klienti', 'Customers')}</SectionLabel>`,
`        <NavSection items={filter(coreNav)} pathname={pathname} />
        <SectionLabel>{t('Výnosy', 'Revenue')}</SectionLabel>
        <NavSection items={filter(revenueNav)} pathname={pathname} />
        <SectionLabel>{t('Klienti', 'Customers')}</SectionLabel>`
);

fs.writeFileSync(file, content);
console.log('Patched');
