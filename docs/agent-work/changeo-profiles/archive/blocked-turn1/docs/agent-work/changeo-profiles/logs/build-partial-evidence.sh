#!/usr/bin/env bash
set -euo pipefail
cd /home/paradis/changeo
task_root=docs/agent-work/changeo-profiles
cp "$task_root/logs/baseline-paths.txt" "$task_root/logs/partial-union-paths.txt"
cat "$task_root/logs/provider-blocked-paths.txt" >> "$task_root/logs/partial-union-paths.txt"
sort -u -o "$task_root/logs/partial-union-paths.txt" "$task_root/logs/partial-union-paths.txt"
: > "$task_root/logs/partial-cumulative.patch"
: > "$task_root/logs/partial-changed-paths.txt"
: > "$task_root/logs/partial-unchanged-paths.txt"
while IFS= read -r source_path; do
  old_path="$task_root/baseline/$source_path"
  new_path="$source_path"
  if [[ ! -f "$old_path" ]]; then old_path=/dev/null; fi
  if [[ ! -f "$new_path" ]]; then new_path=/dev/null; fi
  diff_exit=0
  diff -u --label "a/$source_path" --label "b/$source_path" -- "$old_path" "$new_path" >> "$task_root/logs/partial-cumulative.patch" || diff_exit=$?
  case "$diff_exit" in
    0) printf '%s\n' "$source_path" >> "$task_root/logs/partial-unchanged-paths.txt" ;;
    1) printf '%s\n' "$source_path" >> "$task_root/logs/partial-changed-paths.txt" ;;
    *) exit "$diff_exit" ;;
  esac
done < "$task_root/logs/partial-union-paths.txt"
sha256sum -c "$task_root/logs/provider-blocked-source.sha256" > "$task_root/logs/partial-current-hashes-check.log"
(cd "$task_root/baseline"; sha256sum -c ../logs/baseline.sha256) > "$task_root/logs/partial-baseline-snapshots-check.log"
while IFS= read -r source_path; do
  case "$source_path" in
    src/main/java/ar/changeo/identity/*|src/test/java/ar/changeo/identity/*|src/main/resources/db/migration/V[1-4]__*)
      if [[ -f "$task_root/baseline/$source_path" ]]; then cmp -- "$task_root/baseline/$source_path" "$source_path"; printf '%s\n' "$source_path"; fi ;;
  esac
done < "$task_root/logs/baseline-paths.txt" > "$task_root/logs/partial-foundation-preserved.txt"
printf 'Current source paths: %s\n' "$(wc -l < "$task_root/logs/provider-blocked-paths.txt")"
printf 'Baseline snapshots: %s\n' "$(wc -l < "$task_root/logs/baseline-paths.txt")"
printf 'Changed paths: %s\n' "$(wc -l < "$task_root/logs/partial-changed-paths.txt")"
printf 'Unchanged paths: %s\n' "$(wc -l < "$task_root/logs/partial-unchanged-paths.txt")"
printf 'Preserved foundation and migrations: %s\n' "$(wc -l < "$task_root/logs/partial-foundation-preserved.txt")"
sha256sum "$task_root/logs/partial-cumulative.patch"
