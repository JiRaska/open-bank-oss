const fs = require('fs');
const path = '/Users/jiri.raska/Downloads/OpenBank/openbank-admin-ui/src/app/docs/api/page.tsx';
let content = fs.readFileSync(path, 'utf8');

// The block starts right after the "endpoints" span in the card header.
const searchBlockStart = `{status && (
                  <span style={{ fontSize: '12px', color: 'var(--text-tertiary)', flexShrink: 0 }}>
                    {status.paths.length} endpoints
                  </span>
                )}`;

const targetLinks = `
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
                  Changelog
                </a>
                
                {/* Release Notes link */}
                <a href={\`/docs/release-notes/\${svc.id}\`} target="_blank" rel="noreferrer"
                  onClick={e => e.stopPropagation()}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '4px',
                    padding: '5px 10px', fontSize: '11px', fontWeight: 600,
                    background: '#fdf4ff', border: '1px solid #fbcfe8',
                    borderRadius: '6px', color: '#db2777', textDecoration: 'none',
                    flexShrink: 0,
                  }}>
                  <ExternalLink size={11} />
                  Release Notes
                </a>

                {/* Swagger link */}
                <a href={\`http://localhost:\${svc.port}/q/swagger-ui\`} target="_blank" rel="noreferrer"
                  onClick={e => e.stopPropagation()}
                  style={{
                    display: 'flex', alignItems: 'center', gap: '4px',
                    padding: '5px 10px', fontSize: '11px', fontWeight: 600,
                    background: '#f0fdf4', border: '1px solid #86efac',
                    borderRadius: '6px', color: '#16a34a', textDecoration: 'none',
                    flexShrink: 0,
                  }}>
                  <ExternalLink size={11} />
                  Swagger
                </a>`;

// Find where {status && (...) } ends and {/* Expand toggle */} begins
const split1 = content.split(searchBlockStart);
const beforeLinks = split1[0] + searchBlockStart + '\\n';

const split2 = split1[1].split('{/* Expand toggle */}');
const afterLinks = '\\n                {/* Expand toggle */}' + split2[1];

content = beforeLinks + targetLinks + afterLinks;

fs.writeFileSync(path, content.replace(/\\\\n/g, '\\n'));
console.log('Fixed API links section completely');
