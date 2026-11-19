#!/usr/bin/env bash
set -euo pipefail

initialize_replica_set() {
  local set_name="$1"
  local first_host="$2"
  local second_host="$3"
  local third_host="$4"
  local elected_primary=""
  local member_state=""

  echo "Initializing or checking ${set_name}"

  # For a new set, initialize all three members.
  # If already initialized, leave its current configuration intact for now.
  mongosh --quiet --host "${first_host}:27017" --eval \
    "try {
       rs.status();
       print('already initialized');
     } catch (error) {
       if (error.code === 94 || error.codeName === 'NotYetInitialized') {
         rs.initiate({
           _id: '${set_name}',
           members: [
             { _id: 0, host: '${first_host}:27017' },
             { _id: 1, host: '${second_host}:27017' },
             { _id: 2, host: '${third_host}:27017' }
           ]
         });
       } else {
         throw error;
       }
     }"

  # Wait for an elected primary.
  for attempt in $(seq 1 60); do
    elected_primary="$(mongosh --quiet --host "${first_host}:27017" --eval \
      'try {
         const primary = rs.status().members.find(member => member.stateStr === "PRIMARY");
         print(primary ? primary.name : "");
       } catch (error) {
         print("");
       }' 2>/dev/null || true)"

    if [[ -n "${elected_primary}" ]]; then
      echo "${set_name} elected ${elected_primary}"
      break
    fi

    sleep 2
  done

  if [[ -z "${elected_primary}" ]]; then
    echo "${set_name} did not elect a primary within 120 seconds" >&2
    return 1
  fi

  # For an existing two-member set, add the third member on the primary.
  mongosh --quiet --host "${elected_primary}" --eval \
    "const config = rs.conf();
     if (!config.members.some(member => member.host === '${third_host}:27017')) {
       rs.add({ _id: 2, host: '${third_host}:27017' });
       print('Added ${third_host}:27017 to ${set_name}');
     } else {
       print('${third_host}:27017 is already in ${set_name}');
     }"

  # Wait for the new member to finish joining and become a secondary.
  for attempt in $(seq 1 60); do
    member_state="$(mongosh --quiet --host "${elected_primary}" --eval \
      "try {
         const member = rs.status().members.find(member => member.name === '${third_host}:27017');
         print(member ? member.stateStr : '');
       } catch (error) {
         print('');
       }" 2>/dev/null || true)"

    if [[ "${member_state}" == "SECONDARY" || "${member_state}" == "PRIMARY" ]]; then
      echo "${set_name}: ${third_host}:27017 joined as ${member_state}"
      return 0
    fi

    sleep 2
  done

  echo "${set_name}: ${third_host}:27017 did not become SECONDARY within 120 seconds" >&2
  return 1
}

initialize_replica_set \
  "${RS0_NAME:-rs0}" "${RS0_PRIMARY:-mongo00}" "${RS0_SECONDARY:-mongo01}" "${RS0_TERTIARY:-mongo02}"

initialize_replica_set \
  "${RS1_NAME:-rs1}" "${RS1_PRIMARY:-mongo10}" "${RS1_SECONDARY:-mongo11}" "${RS1_TERTIARY:-mongo12}"

initialize_replica_set \
  "${RS2_NAME:-rs2}" "${RS2_PRIMARY:-mongo20}" "${RS2_SECONDARY:-mongo21}" "${RS2_TERTIARY:-mongo22}"