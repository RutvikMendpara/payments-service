{{- define "payments.image" -}}
{{ .root.Values.image.registry }}payments-{{ .name }}:{{ .root.Values.image.tag }}
{{- end -}}

{{/*
Shared shape for both Spring Boot services. Rolling updates never take a ready pod away
before its replacement is ready, and preStop gives the Service time to stop routing to a
pod before Spring starts its graceful shutdown.
*/}}
{{- define "payments.service" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .name }}
  labels: {app: {{ .name }}}
spec:
  replicas: {{ .replicas }}
  selector:
    matchLabels: {app: {{ .name }}}
  strategy:
    type: RollingUpdate
    rollingUpdate: {maxUnavailable: 0, maxSurge: 1}
  template:
    metadata:
      labels: {app: {{ .name }}}
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/path: /actuator/prometheus
        prometheus.io/port: "{{ .port }}"
    spec:
      terminationGracePeriodSeconds: 45
      containers:
        - name: app
          image: {{ include "payments.image" (dict "root" .root "name" .name) }}
          imagePullPolicy: {{ .root.Values.image.pullPolicy }}
          ports: [{containerPort: {{ .port }}, name: http}]
          env:
{{ toYaml .env | indent 12 }}
          resources:
{{ toYaml .resources | indent 12 }}
          startupProbe:
            httpGet: {path: /actuator/health/liveness, port: http}
            periodSeconds: 3
            failureThreshold: 60
          readinessProbe:
            httpGet: {path: /actuator/health/readiness, port: http}
            periodSeconds: 3
            failureThreshold: 2
          livenessProbe:
            httpGet: {path: /actuator/health/liveness, port: http}
            periodSeconds: 10
            failureThreshold: 3
          lifecycle:
            preStop:
              exec:
                command: ["sh", "-c", "sleep 10"]
---
apiVersion: v1
kind: Service
metadata:
  name: {{ .name }}
spec:
  selector: {app: {{ .name }}}
  ports: [{port: {{ .port }}, targetPort: http, name: http}]
---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: {{ .name }}
spec:
  minAvailable: 1
  selector:
    matchLabels: {app: {{ .name }}}
{{- end -}}
