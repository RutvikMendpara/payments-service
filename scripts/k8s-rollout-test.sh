#!/usr/bin/env bash
# Sends transfers from inside the cluster while ledger-service is restarted pod by pod.
# Zero-downtime means no failed requests in the k6 summary.
set -euo pipefail
cd "$(dirname "$0")/.."

kubectl delete pod k6 --ignore-not-found >/dev/null
kubectl create configmap k6-script --from-file=scripts/transfers.k6.js --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata:
  name: k6
spec:
  restartPolicy: Never
  containers:
    - name: k6
      image: grafana/k6:1.6.1
      args: ["run", "--quiet", "/scripts/transfers.k6.js"]
      env:
        - {name: LEDGER_URL, value: "http://ledger-service:8081"}
        - {name: TOKEN_URI, value: "http://keycloak:8080/realms/payments/protocol/openid-connect/token"}
        - {name: VUS, value: "20"}
        - {name: DURATION, value: "120s"}
      volumeMounts: [{name: scripts, mountPath: /scripts}]
  volumes:
    - name: scripts
      configMap: {name: k6-script}
YAML

kubectl wait --for=condition=Ready pod/k6 --timeout=180s >/dev/null
sleep 25
echo "rolling ledger-service while k6 runs..."
kubectl rollout restart deployment/ledger-service
kubectl rollout status deployment/ledger-service --timeout=180s
kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/k6 --timeout=240s >/dev/null || true
kubectl logs k6 | grep -E "checks_total|checks_failed|http_req_failed|name:POST /transfers \}"
