const fs = require('fs')

const file = 'src/app/docs/bpmn/page.tsx'
let content = fs.readFileSync(file, 'utf8')

// Add imports for icons
content = content.replace(
  "import { GitBranch } from 'lucide-react'",
  "import { GitBranch, RefreshCw, CheckCircle2, XCircle, AlertCircle } from 'lucide-react'"
)

// Add ProcessLayerMap component before BpmnPage
const processLayerMapStr = `
function ProcessLayerMap({ process }: { process: typeof PROCESSES[0] }) {
  const [statuses, setStatuses] = useState<Record<string, 'up' | 'down' | 'loading' | 'unknown'>>({})
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)

  const stepsWithDetails = process.steps.filter(s => s.apis?.length || s.relatedServices?.length)

  const checkServices = async () => {
    const servicesToCheck = Array.from(new Set(stepsWithDetails.flatMap(s => s.relatedServices || [])))
    
    // Set loading
    const newStatuses = { ...statuses }
    servicesToCheck.forEach(s => newStatuses[s] = 'loading')
    setStatuses(newStatuses)

    await Promise.all(servicesToCheck.map(async (svc) => {
      const port = SERVICE_PORTS[svc]
      if (!port) {
        setStatuses(prev => ({ ...prev, [svc]: 'unknown' }))
        return
      }
      try {
        const res = await fetch(\`http://localhost:\${port}/q/health/ready\`, { signal: AbortSignal.timeout(3000) })
        setStatuses(prev => ({ ...prev, [svc]: res.ok ? 'up' : 'down' }))
      } catch (err) {
        setStatuses(prev => ({ ...prev, [svc]: 'down' }))
      }
    }))
    
    setLastRefresh(new Date())
  }

  return (
    <div style={{ marginTop: '24px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
        <h3 style={{ fontSize: '14px', fontWeight: 600, color: 'var(--text-primary)', margin: 0 }}>
          API & Service Coverage (Layered View)
        </h3>
        <button 
          onClick={checkServices}
          style={{ 
            display: 'flex', alignItems: 'center', gap: '6px', 
            padding: '6px 12px', fontSize: '12px', fontWeight: 500, 
            background: 'var(--surface-2)', border: '1px solid var(--border)', 
            borderRadius: '6px', color: 'var(--text-secondary)', cursor: 'pointer' 
          }}>
          <RefreshCw size={14} />
          {lastRefresh ? \`Refreshed \${lastRefresh.toLocaleTimeString()}\` : 'Check Status'}
        </button>
      </div>

      <div style={{ display: 'grid', gap: '12px' }}>
        {stepsWithDetails.map(step => (
          <div key={step.id} style={{ 
            display: 'grid', gridTemplateColumns: '1.5fr 2fr 1.5fr', gap: '16px', 
            padding: '12px', background: 'var(--surface)', border: '1px solid var(--border)', 
            borderRadius: '8px', alignItems: 'center' 
          }}>
            <div>
              <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', marginBottom: '4px' }}>Process Step</div>
              <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-primary)' }}>{step.label}</div>
              {step.lane && (
                <div style={{ fontSize: '11px', color: 'var(--text-secondary)', marginTop: '2px' }}>Lane: {step.lane}</div>
              )}
            </div>

            <div>
              <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', marginBottom: '4px' }}>APIs</div>
              {step.apis?.length ? step.apis.map(api => (
                <div key={api} style={{ 
                  fontSize: '12px', fontFamily: 'JetBrains Mono, monospace', 
                  color: 'var(--text-secondary)', background: 'var(--surface-2)', 
                  padding: '4px 8px', borderRadius: '4px', display: 'inline-block', marginBottom: '4px' 
                }}>
                  {api}
                </div>
              )) : <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>No API mapped</div>}
            </div>

            <div>
              <div style={{ fontSize: '11px', color: 'var(--text-tertiary)', textTransform: 'uppercase', marginBottom: '4px' }}>Services & Status</div>
              {step.relatedServices?.length ? step.relatedServices.map(svc => {
                const status = statuses[svc]
                return (
                  <div key={svc} style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                    <div style={{ fontSize: '12px', fontWeight: 500, color: 'var(--text-secondary)' }}>{svc}</div>
                    {status === 'up' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#16a34a', fontWeight: 600, background: '#dcfce7', padding: '2px 6px', borderRadius: '4px' }}><CheckCircle2 size={12}/> UP</span>}
                    {status === 'down' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#dc2626', fontWeight: 600, background: '#fee2e2', padding: '2px 6px', borderRadius: '4px' }}><XCircle size={12}/> DOWN</span>}
                    {status === 'loading' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#d97706', fontWeight: 600, background: '#fef3c7', padding: '2px 6px', borderRadius: '4px' }}><RefreshCw size={12} className="animate-spin" /> ...</span>}
                    {status === 'unknown' && <span style={{ display: 'flex', alignItems: 'center', gap: '4px', fontSize: '11px', color: '#6b7280', fontWeight: 600, background: '#f3f4f6', padding: '2px 6px', borderRadius: '4px' }}><AlertCircle size={12}/> N/A</span>}
                  </div>
                )
              }) : <div style={{ fontSize: '12px', color: 'var(--text-tertiary)' }}>No service mapped</div>}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
`

content = content.replace('export default function BpmnPage() {', processLayerMapStr + '\nexport default function BpmnPage() {')

// Add <ProcessLayerMap process={process} /> below the Services involved div
content = content.replace(
  '        <div style={{ marginTop: \'16px\', paddingTop: \'16px\', borderTop: \'1px solid var(--border)\' }}>',
  '        <ProcessLayerMap process={process} />\n\n        <div style={{ marginTop: \'16px\', paddingTop: \'16px\', borderTop: \'1px solid var(--border)\' }}>'
)

fs.writeFileSync(file, content)
