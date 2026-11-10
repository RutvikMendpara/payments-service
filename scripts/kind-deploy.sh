#!/usr/bin/env bash
# Builds the images, loads them into a local kind cluster and installs the chart.
set -euo pipefail
cd "$(dirname "$0")/.."

cluster=payments
kind get clusters | grep -qx "$cluster" || kind create cluster --name "$cluster"
kubectl config use-context "kind-$cluster" >/dev/null

docker compose build ledger-service settlement-service
kind load docker-image --name "$cluster" payments-ledger-service:latest payments-settlement-service:latest

kubectl create configmap keycloak-realm --from-file=keycloak/payments-realm.json --dry-run=client -o yaml | kubectl apply -f -
helm upgrade --install payments helm/payments --wait --timeout 10m
kubectl get pods
