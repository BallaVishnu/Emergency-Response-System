#!/usr/bin/env bash
set -euo pipefail

initialize_replica_set() {
  local set_name="$1"
  local primary_host="$2"
  local secondary_host="$3"
  local elected_primary

  echo "Initializing or checking ${set_name} (${primary_host}, ${secondary_host})"

  mongosh --quiet --host "${primary_host}:27017" --eval \
    "try { rs.status(); print('already initialized'); } catch (error) { if (error.code === 94 || error.codeName === 'NotYetInitialized') { rs.initiate({_id: '${set_name}', members: [{_id: 0, host: '${primary_host}:27017'}, {_id: 1, host: '${secondary_host}:27017'}]}); } else { throw error; } }"

  for attempt in $(seq 1 60); do
    elected_primary="$(mongosh --quiet --host "${primary_host}:27017" --eval \
      'try { const primary = rs.status().members.find(member => member.stateStr === "PRIMARY"); print(primary ? primary.name : ""); } catch (error) { print(""); }' 2>/dev/null || true)"
    if [[ -n "${elected_primary}" ]]; then
      echo "${set_name} elected ${elected_primary}"
      return 0
    fi
    sleep 2
  done

  echo "${set_name} did not elect a primary within 120 seconds" >&2
  return 1
}

initialize_replica_set "${RS0_NAME:-rs0}" "${RS0_PRIMARY:-mongo00}" "${RS0_SECONDARY:-mongo01}"
initialize_replica_set "${RS1_NAME:-rs1}" "${RS1_PRIMARY:-mongo10}" "${RS1_SECONDARY:-mongo11}"
initialize_replica_set "${RS2_NAME:-rs2}" "${RS2_PRIMARY:-mongo20}" "${RS2_SECONDARY:-mongo21}"
