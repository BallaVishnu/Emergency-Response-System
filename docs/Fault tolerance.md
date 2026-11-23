# Fault Tolerance and Recovery Demonstration

Demo of - Kubernetes application-pod recovery, MongoDB replica-set recovery, and service availability. Run the commands in separate terminals so that pod status can be observed continuously.

Before starting, deploy the application:

```bash
./scripts/deploy-minikube.sh
```

## 1. Verify the Initial System State

Open the frontend:

```bash
minikube service frontend -n emergency --url
```

List all running pods:

```bash
kubectl -n emergency get pods -o wide
```

**Why:** This records the normal state and confirms that the API, worker, frontend, RabbitMQ, and MongoDB pods are running before the failure is introduced.

Check deployments:

```bash
kubectl -n emergency get deployments
```

Check services:

```bash
kubectl -n emergency get services
```

**Why:** Services provide stable endpoints while individual pods are stopped or replaced.

Check the API health endpoint:

```bash
kubectl -n emergency port-forward service/api 8000:8000
```

In another terminal:

```bash
curl http://localhost:8000/health
curl http://localhost:8000/health/ready
```

**Why:** `/health` verifies that the API process is running. `/health/ready` verifies that it can reach MongoDB storage.

## 2. Demonstrate Matching Worker Pod Failure

Watch worker pods in one terminal:

```bash
kubectl -n emergency get pods -l app=matching-worker -w
```

In another terminal, stop one worker pod:

```bash
kubectl -n emergency delete pod -l app=matching-worker --wait=false
```

**Why:** Kubernetes deletes one matching-worker pod without deleting the Deployment. This demonstrates automatic recovery of an application pod.

Observe pod replacement:

```bash
kubectl -n emergency get pods -l app=matching-worker
kubectl -n emergency rollout status deployment/matching-worker --timeout=300s
```

**Why:** A new pod should be created, become Ready, and return the deployment to its configured replica count.

Verify the deployment desired and ready replica counts:

```bash
kubectl -n emergency get deployment matching-worker
```

**Why:** The `READY` column should return to the configured replica count, such as `2/2`.

Verify that worker service processing remains available:

```bash
kubectl -n emergency logs deployment/matching-worker --tail=50
```

**Why:** Worker logs confirm that the replacement pod starts and can connect to RabbitMQ and MongoDB.

If needed, continue using the dashboard or send a new test request while monitoring recovery. The API and RabbitMQ remain independent of the deleted worker pod.

## 3. Demonstrate API Pod Failure

Watch API pods:

```bash
kubectl -n emergency get pods -l app=api -w
```

Stop one API pod:

```bash
kubectl -n emergency delete pod -l app=api --wait=false
```

**Why:** This demonstrates that the API Deployment replaces a failed API pod and Kubernetes continues routing traffic through the API Service.

Check recovery:

```bash
kubectl -n emergency get pods -l app=api
kubectl -n emergency rollout status deployment/api --timeout=300s
```

Verify the API after recovery:

```bash
kubectl -n emergency port-forward service/api 8000:8000
```

In another terminal:

```bash
curl http://localhost:8000/health
curl http://localhost:8000/health/ready
```

**Why:** The API should become reachable again through the stable Kubernetes Service, even though one pod was replaced.

## 4. Demonstrate MongoDB Member Failure

List MongoDB members for shard 0:

```bash
kubectl -n emergency get pods -l app=mongo-rs0 -o wide
```

Check the replica-set primary and member states:

```bash
kubectl -n emergency exec mongo-rs0-1 -- mongosh --quiet --eval '
  rs.status().members.map(function(member) {
    return { name: member.name, state: member.stateStr, health: member.health };
  })
'
```

**Why:** This shows which member is `PRIMARY` and which members are `SECONDARY` before the failure.

Stop one secondary member, if `mongo-rs0-1` is a secondary:

```bash
kubectl -n emergency delete pod mongo-rs0-1 --wait=false
```

**Why:** Stopping a secondary demonstrates that reads and writes can continue when a data replica is unavailable.

Watch MongoDB pods:

```bash
kubectl -n emergency get pods -l app=mongo-rs0 -w
```

Check the replica-set status during recovery:

```bash
kubectl -n emergency exec mongo-rs0-0 -- mongosh --quiet --eval 'rs.status().members'
```

Wait for the deleted member to restart:

```bash
kubectl -n emergency wait pod/mongo-rs0-1 --for=condition=Ready --timeout=300s
```

Verify the replica-set state again:

```bash
kubectl -n emergency exec mongo-rs0-1 -- mongosh --quiet --eval '
  rs.status().members.map(function(member) {
    return { name: member.name, state: member.stateStr, health: member.health };
  })
'
```

**Why:** The restarted member should appear again and eventually return to `SECONDARY` after synchronizing data.

## 5. Demonstrate MongoDB Primary Failure

Check the primary before deleting it:

```bash
kubectl -n emergency exec mongo-rs0-1 -- mongosh --quiet --eval '
  rs.status().members.filter(function(member) {
    return member.stateStr === "PRIMARY";
  }).map(function(member) { return member.name; })
'
```

The result may be `mongo-rs0-0.mongo-rs0:27017`, `mongo-rs0-1.mongo-rs0:27017`, or `mongo-rs0-2.mongo-rs0:27017`.

Stop the shown primary pod. For example, if the primary is pod `mongo-rs0-0`:

```bash
kubectl -n emergency delete pod mongo-rs0-0 --wait=false
```

**Why:** This demonstrates MongoDB primary failure and automatic leader election among surviving replica-set members.

Wait for a replacement pod:

```bash
kubectl -n emergency wait pod/mongo-rs0-0 --for=condition=Ready --timeout=300s
```

Check new member states:

```bash
kubectl -n emergency exec mongo-rs0-1 -- mongosh --quiet --eval '
  rs.status().members.map(function(member) {
    return { name: member.name, state: member.stateStr, health: member.health };
  })
'
```

**Why:** One surviving secondary should become `PRIMARY`, while the restarted member should return as `SECONDARY` after synchronization.

Verify the API remains usable:

```bash
curl http://localhost:8000/health/ready
curl http://localhost:8000/stats
```

**Why:** This confirms that the application can continue to access MongoDB through the replica-set connection and that the API remains available.

## 6. Verify API and Storage Operations During Recovery

Create a request before or during the demonstration:

```bash
curl -X POST http://localhost:8000/requests \
  -H 'Content-Type: application/json' \
  -d '{
    "location":"Central Camp",
    "latitude":13.02,
    "longitude":77.55,
    "request_type":"water",
    "description":"Drinking water needed",
    "quantity":10,
    "urgency_level":4
  }'
```

List requests:

```bash
curl http://localhost:8000/requests
```

**Why:** Request creation and retrieval verify that API and storage operations remain available during pod recovery.

Check RabbitMQ queue readiness:

```bash
kubectl -n emergency get pods -l app=rabbitmq
kubectl -n emergency logs deployment/rabbitmq --tail=30
```

**Why:** This confirms that asynchronous matching events can still be delivered through RabbitMQ.

## 7. Demonstrate Worker Scaling

Scale workers manually:

```bash
./scripts/scale-service.sh 4 matching-worker
```

Or use `kubectl` directly:

```bash
kubectl -n emergency scale deployment/matching-worker --replicas=4
kubectl -n emergency rollout status deployment/matching-worker --timeout=300s
kubectl -n emergency get pods -l app=matching-worker
```

**Why:** This demonstrates Kubernetes horizontal scaling. Additional workers consume RabbitMQ work concurrently.

Return to the default replica count:

```bash
kubectl -n emergency scale deployment/matching-worker --replicas=2
kubectl -n emergency rollout status deployment/matching-worker --timeout=300s
```

**Why:** This restores the original deployment configuration after the scaling demonstration.

## 8. Verify HPA Behavior

Check the HPA:

```bash
kubectl -n emergency get hpa
kubectl -n emergency describe hpa matching-worker
```

**Why:** The HPA shows current CPU usage and the current replica count. The worker can scale between 2 and 6 replicas when metrics-server data is available.

Observe HPA scaling:

```bash
kubectl -n emergency get hpa -w
```

Stop watching with `Ctrl+C`.

## 9. Restore the Normal State

Restore worker replicas:

```bash
kubectl -n emergency scale deployment/matching-worker --replicas=2
```

Restore API replicas:

```bash
kubectl -n emergency scale deployment/api --replicas=2
```

Restore frontend replicas:

```bash
kubectl -n emergency scale deployment/frontend --replicas=2
```

Check all pods:

```bash
kubectl -n emergency get pods -o wide
```

**Why:** This leaves the deployed system at its original replica counts after demonstrations.

