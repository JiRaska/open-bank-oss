{{- define "product-catalog.name" -}}
product-catalog
{{- end }}

{{- define "product-catalog.labels" -}}
app.kubernetes.io/name: {{ include "product-catalog.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
