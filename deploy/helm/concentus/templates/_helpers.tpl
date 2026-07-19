{{- define "concentus.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "concentus.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "concentus.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "concentus.labels" -}}
app.kubernetes.io/name: {{ include "concentus.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "concentus.backendName" -}}{{ include "concentus.fullname" . }}-backend{{- end -}}
{{- define "concentus.frontendName" -}}{{ include "concentus.fullname" . }}-frontend{{- end -}}
{{- define "concentus.nginxName" -}}{{ include "concentus.fullname" . }}-nginx{{- end -}}

{{- define "concentus.authSecretName" -}}
{{- if .Values.backend.existingSecret -}}
{{- .Values.backend.existingSecret -}}
{{- else -}}
{{- printf "%s-auth" (include "concentus.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- define "concentus.dbSecretName" -}}
{{- if .Values.postgresql.existingSecret -}}
{{- .Values.postgresql.existingSecret -}}
{{- else -}}
{{- printf "%s-db" (include "concentus.fullname" .) -}}
{{- end -}}
{{- end -}}

{{- /*
Password for the bundled database.

An explicit value wins. Otherwise the password already stored in the cluster is reused, so a
`helm upgrade` doesn't mint a new one and leave the backend authenticating against a database
that still has the old one. Only a genuinely first install generates a fresh password.
`lookup` returns nil on install and during `helm template`/dry-run, hence the guards.
*/}}
{{- define "concentus.dbPassword" -}}
{{- if .Values.postgresql.password -}}
{{- .Values.postgresql.password -}}
{{- else -}}
{{- $name := printf "%s-db" (include "concentus.fullname" .) -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace $name -}}
{{- if and $existing $existing.data (index $existing.data "password") -}}
{{- index $existing.data "password" | b64dec -}}
{{- else -}}
{{- randAlphaNum 24 -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "concentus.hasManagedSecret" -}}
{{- if and (not .Values.backend.existingSecret) (or .Values.backend.claudeOAuthToken .Values.backend.anthropicApiKey) -}}true{{- end -}}
{{- end -}}
