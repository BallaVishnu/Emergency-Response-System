#!/usr/bin/env bash
set -euo pipefail

minikube image build -t emergency-frontend:latest ./frontend

kubectl -n emergency rollout restart deployment/frontend
kubectl -n emergency rollout status deployment/frontend --timeout=300s