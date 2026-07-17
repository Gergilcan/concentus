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

{{- define "concentus.hasManagedSecret" -}}
{{- if and (not .Values.backend.existingSecret) (or .Values.backend.claudeOAuthToken .Values.backend.anthropicApiKey) -}}true{{- end -}}
{{- end -}}
