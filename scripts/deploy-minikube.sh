#!/usr/bin/env bash
set -euo pipefail

NAMESPACE="emergency"

if [[ ! -f .env ]]; then
  echo "Missing .env" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a


: "${MONGO_DB:?Set MONGO_DB in .env}"
: "${K8S_MONGO_SHARD0_URI:?Set K8S_MONGO_SHARD0_URI in .env}"
: "${K8S_MONGO_SHARD1_URI:?Set K8S_MONGO_SHARD1_URI in .env}"
: "${K8S_MONGO_SHARD2_URI:?Set K8S_MONGO_SHARD2_URI in .env}"
: "${RABBITMQ_USER:?Set RABBITMQ_USER in .env}"
: "${RABBITMQ_PASSWORD:?Set RABBITMQ_PASSWORD in .env}"
: "${MATCHING_RADIUS_KM:=100}"
: "${HEARTBEAT_TIMEOUT_SECONDS:=30}"

export K8S_RABBITMQ_URL="amqp://${RABBITMQ_USER}:${RABBITMQ_PASSWORD}@rabbitmq:5672/"

minikube start --driver=docker
minikube addons enable metrics-server

minikube image build -t emergency-api:latest ./backend
minikube image build -t emergency-frontend:latest ./frontend


# Create the namespace before applying resources that use it.
kubectl apply -f kubernetes/00-namespace.yaml
# Render environment-dependent config locally; credentials are not stored in Git.
envsubst < kubernetes/01-config.yaml | kubectl apply -f -

kubectl apply -f kubernetes/02-mongodb.yaml
kubectl apply -f kubernetes/03-rabbitmq.yaml
kubectl apply -f kubernetes/04-backend.yaml
kubectl apply -f kubernetes/05-frontend.yaml
kubectl apply -f kubernetes/06-autoscaling.yaml

initialize_replica_set() {
  local set_name="$1"
  local first_pod="$2"
  local second_pod="$3"
  local third_pod="$4"
  local service="$5"
  local elected_primary=""
  local third_member_state=""

  local first_host="${first_pod}.${service}:27017"
  local second_host="${second_pod}.${service}:27017"
  local third_host="${third_pod}.${service}:27017"

  echo "Waiting for StatefulSet ${service}"
  kubectl -n "$NAMESPACE" rollout status \
    "statefulset/${service}" --timeout=300s

  echo "Initializing or updating ${set_name}"

  # A fresh set starts with all three members. Existing sets are left intact
  # here; the third member is added after a primary is elected.
  kubectl -n "$NAMESPACE" exec "$first_pod" -- mongosh --quiet --eval \
    "try {
       rs.status();
       print('already initialized');
     } catch (error) {
       if (error.code === 94 || error.codeName === 'NotYetInitialized') {
       try {
         rs.initiate({
           _id: '${set_name}',
           members: [
             { _id: 0, host: '${first_host}' },
             { _id: 1, host: '${second_host}' },
             { _id: 2, host: '${third_host}' }
           ]
         });
         print('initialized ${set_name} with three members');
        }catch(initError){
          if(initError.code === 23 || initError.codeName === 'AlreadyInitialized') {
            print('${set_name} is already initialized, continuing.');
          } else {
            throw initError;
          }
        }
       } else {
         throw error;
       }
     }"

  # Find the elected primary. rs.add must be run against the primary.
  for attempt in $(seq 1 90); do
    elected_primary="$(
      kubectl -n "$NAMESPACE" exec "$first_pod" -- mongosh --quiet --eval \
        'try {
           const primary = rs.status().members.find(member => member.stateStr === "PRIMARY");
           print(primary ? primary.name : "");
         } catch (error) {
           print("");
         }' 2>/dev/null || true
    )"

    if [[ -n "$elected_primary" ]]; then
      echo "${set_name} elected ${elected_primary}"
      break
    fi

    sleep 2
  done

  if [[ -z "$elected_primary" ]]; then
    echo "${set_name} did not elect a primary within 180 seconds" >&2
    return 1
  fi

  # Add the third member if this set was previously initialized with two.
  kubectl -n "$NAMESPACE" exec "$first_pod" -- \
    mongosh --quiet --host "$elected_primary" --eval \
    "const config = rs.conf();
     if (!config.members.some(member => member.host === '${third_host}')) {
       rs.add({ _id: 2, host: '${third_host}' });
       print('Added ${third_host} to ${set_name}');
     } else {
       print('${third_host} is already in ${set_name}');
     }"

  # Wait until the third member has joined and caught up.
  for attempt in $(seq 1 90); do
    third_member_state="$(
      kubectl -n "$NAMESPACE" exec "$first_pod" -- \
        mongosh --quiet --host "$elected_primary" --eval \
        "try {
           const member = rs.status().members.find(member => member.name === '${third_host}');
           print(member ? member.stateStr : '');
         } catch (error) {
           print('');
         }" 2>/dev/null || true
    )"

    if [[ "$third_member_state" == "SECONDARY" || "$third_member_state" == "PRIMARY" ]]; then
      echo "${set_name}: ${third_host} joined as ${third_member_state}"
      return 0
    fi

    sleep 2
  done

  echo "${set_name}: ${third_host} did not join within 180 seconds" >&2
  return 1
}

initialize_replica_set rs0 mongo-rs0-0 mongo-rs0-1 mongo-rs0-2 mongo-rs0
initialize_replica_set rs1 mongo-rs1-0 mongo-rs1-1 mongo-rs1-2 mongo-rs1
initialize_replica_set rs2 mongo-rs2-0 mongo-rs2-1 mongo-rs2-2 mongo-rs2

kubectl -n "$NAMESPACE" rollout restart deployment/api deployment/matching-worker

kubectl -n "$NAMESPACE" rollout status deployment/api --timeout=300s
kubectl -n "$NAMESPACE" rollout status deployment/matching-worker --timeout=300s
kubectl -n "$NAMESPACE" rollout status deployment/frontend --timeout=300s
kubectl -n "$NAMESPACE" get pods

echo "Frontend URL: $(minikube service frontend -n "$NAMESPACE" --url)"


# afte restarting laptop:
# minikube start --driver=docker
# kubectl -n emergency get pods
# minikube service frontend -n emergency --url

#after changing code, you can rebuild using:
# minikube image build -t emergency-api:latest ./backend
# kubectl -n emergency rollout restart deployment/api deployment/matching-worker
# kubectl -n emergency rollout status deployment/api --timeout=300s
# kubectl -n emergency rollout status deployment/matching-worker --timeout=300s

#frontend changes
# minikube image build -t emergency-frontend:latest ./frontend
# kubectl -n emergency rollout restart deployment/frontend

#delete kubernetes namespace:
#kubectl delete namespace emergency

#delete kubernetes mongodb data:
# kubectl -n emergency get pvc
# kubectl -n emergency scale statefulset --all --replicas=0
# kubectl -n emergency delete pvc data-mongo-rs0-0 data-mongo-rs0-1 data-mongo-rs0-2 \
#   data-mongo-rs1-0 data-mongo-rs1-1 data-mongo-rs1-2 \
#   data-mongo-rs2-0 data-mongo-rs2-1 data-mongo-rs2-2
