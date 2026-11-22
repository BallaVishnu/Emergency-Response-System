#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="emergency"
REPLICAS="${1:-4}"
DEPLOYMENT="${2:-matching-worker}"

if ! [[ "$REPLICAS" =~ ^[1-9][0-9]*$ ]]; then
  echo "Usage: $0 [positive-replica-count] [deployment]" >&2
  echo "Example: $0 4 matching-worker" >&2
  exit 1
fi

case "$DEPLOYMENT" in
  api|matching-worker|frontend)
    ;;
  *)
    echo "Unsupported deployment: $DEPLOYMENT" >&2
    echo "Choose api, matching-worker, or frontend." >&2
    exit 1
    ;;
esac

if ! kubectl -n "$NAMESPACE" get deployment "$DEPLOYMENT" >/dev/null 2>&1; then
  echo "Deployment $DEPLOYMENT was not found in namespace $NAMESPACE." >&2
  exit 1
fi

if [[ "$DEPLOYMENT" == "matching-worker" ]] \
  && kubectl -n "$NAMESPACE" get hpa matching-worker >/dev/null 2>&1 \
  && (( REPLICAS > 6 )); then
  echo "matching-worker has an HPA configured with a maximum of 6 replicas." >&2
  exit 1
fi

echo "Scaling $DEPLOYMENT to $REPLICAS replicas in namespace $NAMESPACE..."

kubectl -n "$NAMESPACE" scale deployment "$DEPLOYMENT" \
  --replicas="$REPLICAS"

kubectl -n "$NAMESPACE" rollout status "deployment/$DEPLOYMENT" \
  --timeout=300s

kubectl -n "$NAMESPACE" get pods -l "app=$DEPLOYMENT"