{{/*
_helpers.tpl — reusable template snippets ("named templates"). Files starting with "_" render nothing
on their own; other templates call these with {{ include "name" context }}.
*/}}

{{/* Labels every Pulse object carries. Kubernetes "recommended labels" (app.kubernetes.io/*). */}}
{{- define "pulse.commonLabels" -}}
app.kubernetes.io/part-of: pulse
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end }}

{{/* Labels for one component. Called with a dict: (dict "name" $name "root" $). */}}
{{- define "pulse.labels" -}}
{{ include "pulse.selectorLabels" . }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
{{ include "pulse.commonLabels" .root }}
{{- end }}

{{/* The SELECTOR labels: must never change after the first install (Deployments' selectors are immutable). */}}
{{- define "pulse.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{/* Ownership annotations, added to top-level objects. */}}
{{- define "pulse.annotations" -}}
pulse/designed-and-developed-by: "Ankur Kumar"
pulse/owner: "Ravenclaw_007 LLC"
{{- end }}

{{/* "repository:tag", with an optional registry prefix from .Values.global.imageRegistry. */}}
{{- define "pulse.image" -}}
{{- $reg := .root.Values.global.imageRegistry -}}
{{- if $reg -}}{{ printf "%s/%s:%s" $reg .image.repository .image.tag }}{{- else -}}{{ printf "%s:%s" .image.repository .image.tag }}{{- end -}}
{{- end }}
