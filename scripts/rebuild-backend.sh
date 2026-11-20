#!/usr/bin/env bash
set -euo pipefail

minikube image build -t emergency-api:latest ./backend

kubectl -n emergency rollout restart deployment/api deployment/matching-worker
kubectl -n emergency rollout status deployment/api --timeout=300s
kubectl -n emergency rollout status deployment/matching-worker --timeout=300s