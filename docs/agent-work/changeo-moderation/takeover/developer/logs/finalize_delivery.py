import json
from pathlib import Path

from preservation import fingerprint, read_json, save_json


def main() -> None:
    workspace = Path(__file__).resolve().parents[6]
    delivery = workspace / "docs/agent-work/changeo-moderation/takeover/developer"
    logs = delivery / "logs"
    commands = []
    for path in sorted(logs.glob("*.exit")):
        log = path.with_suffix(".log")
        commands.append({"name": path.stem, "exit": int(path.read_text(encoding="utf-8").strip()), "exit_file": str(path.relative_to(workspace)), "log": str(log.relative_to(workspace)) if log.is_file() else None, "log_sha256": fingerprint(log) if log.is_file() else None})
    save_json(logs / "command-summary.json", commands)
    expected = {"MFR3-first": 1, "MFR3-host-first": 0, "verify-first": 0, "first-correction-integrity": 1, "correction-integrity": 0, "first-preservation": 1, "preservation": 0, "runtime-cleanup": 0}
    exits_match = all(next(entry["exit"] for entry in commands if entry["name"] == name) == value for name, value in expected.items())
    verification = read_json(logs / "verification-summary.json")
    source = read_json(logs / "source-manifest.json")
    current_sources_match = all(fingerprint(workspace / entry["path"]) == entry["sha256"] for entry in source)
    preservation = read_json(logs / "preservation-summary.json")
    integrity = read_json(logs / "correction-integrity.json")
    runtime = read_json(logs / "runtime-cleanup.json")
    passed = exits_match and current_sources_match and verification["total"] == 161 and verification["container_executions"] == 56 and all(verification[name] == 0 for name in ("failures", "errors", "skipped")) and preservation["baseline_snapshots_preserved"] and preservation["F3_snapshots_preserved"] and preservation["F3_seals_preserved"] and preservation["foundation_identity_tests_V1_V3_preserved"] and not preservation["unauthorized_source_paths"] and not preservation["markdown"]["errors"] and integrity["passed"] and runtime["passed"]
    excluded = {logs / "final-artifacts.json", logs / "complete-change-manifest.json"}
    artifacts = []
    for path in sorted(delivery.rglob("*")):
        if path.is_file() and path not in excluded and "__pycache__" not in path.parts:
            artifacts.append({"path": str(path.relative_to(workspace)), "size": path.stat().st_size, "sha256": fingerprint(path)})
    manifest = {"files": [*source, *artifacts], "self_hash_exclusions": [str(path.relative_to(workspace)) for path in sorted(excluded)], "root_index_attribution": "unowned-inputs.json", "runtime_secret_exclusions": [".runtime", ".local-tools", "target", "protected metadata contents"]}
    save_json(logs / "complete-change-manifest.json", manifest)
    seals = [*artifacts, {"path": str((logs / "complete-change-manifest.json").relative_to(workspace)), "size": (logs / "complete-change-manifest.json").stat().st_size, "sha256": fingerprint(logs / "complete-change-manifest.json")}]
    result = {"status": "ready_for_review" if passed else "failed", "task_id": "M1/MFR1-MFR3", "workspace": str(workspace), "worker": "/root/moderation_takeover/moderation_builder_takeover", "host_id": "01a0f991-38ed-7da3-994e-1101f01350f7", "coordinator": "/root/moderation_takeover", "acceptance": "pending separate independent final reviewer", "checks": {"passed": passed, "exit_expectations_match": exits_match, "source_fingerprints": len(source), "current_sources_match": current_sources_match, "executions": verification["total"], "actual_container_executions": verification["container_executions"], "baseline289_preserved": preservation["baseline_snapshots_preserved"], "F3_source87_and_seals6_preserved": preservation["F3_snapshots_preserved"] and preservation["F3_seals_preserved"], "history_api_append": integrity["old_api"], "historical_checkpoint": integrity["preexisting_correction_checkpoint"]}, "takeover_product_paths": integrity["takeover_source_changes"], "decisions_required": [], "runtime": runtime, "seals": seals, "self_hash_exclusions": [str((logs / "final-artifacts.json").relative_to(workspace))], "next_action": "Coordinator checks durable handoff and dispatches fresh independent cumulative final reviewer; same writer receives corrections."}
    save_json(logs / "final-artifacts.json", result)
    seal_check = all(fingerprint(workspace / entry["path"]) == entry["sha256"] for entry in seals)
    print(json.dumps({"status": result["status"], "source_fingerprints": len(source), "artifact_seals": len(seals), "seals_match": seal_check, "checks_passed": passed}))
    if not passed or not seal_check:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
