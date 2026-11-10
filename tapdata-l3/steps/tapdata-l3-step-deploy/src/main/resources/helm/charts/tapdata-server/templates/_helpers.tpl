{{/*
Expand the name of the chart.
*/}}
{{- define "tapdata-server.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "tapdata-server.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "tapdata-server.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "tapdata-server.labels" -}}
helm.sh/chart: {{ include "tapdata-server.chart" . }}
{{ include "tapdata-server.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "tapdata-server.selectorLabels" -}}
app.kubernetes.io/name: {{ include "tapdata-server.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Construct image reference with optional global.imageRegistry prefix.
When global.imageRegistry is set, image = "registry/repository:tag"
When global.imageRegistry is empty, image = "repository:tag"
*/}}
{{- define "tapdata-server.image" -}}
{{- if .Values.global.imageRegistry -}}
{{ .Values.global.imageRegistry }}/{{ .Values.image.repository }}:{{ .Values.image.tag }}
{{- else -}}
{{ .Values.image.repository }}:{{ .Values.image.tag }}
{{- end -}}
{{- end -}}

{{/*
Construct kubectl init container image with optional global.imageRegistry prefix.
*/}}
{{- define "tapdata-server.kubectlImage" -}}
{{- if .Values.global.imageRegistry -}}
{{ .Values.global.imageRegistry }}/{{ .Values.image.kubectlRepository }}:{{ .Values.image.kubectlTag }}
{{- else -}}
{{ .Values.image.kubectlRepository }}:{{ .Values.image.kubectlTag }}
{{- end -}}
{{- end -}}
