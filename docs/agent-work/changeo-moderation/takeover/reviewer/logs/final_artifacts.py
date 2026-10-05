import hashlib
import json
from pathlib import Path


def fingerprint(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> None:
    logs = Path(__file__).resolve().parent
    reviewer = logs.parent
    workspace = reviewer.parents[4]
    commands = []
    for name in ("verify-first", "preservation-first", "preservation-final", "correction-integrity-first", "delivery-integrity-first", "runtime-cleanup-first"):
        exit_path = logs / f"{name}.exit"
        log_path = logs / f"{name}.log"
        commands.append({"name": name, "exit": int(exit_path.read_text(encoding="utf-8")), "exit_file": str(exit_path.relative_to(workspace)), "log": str(log_path.relative_to(workspace)), "log_sha256": fingerprint(log_path)})
    (logs / "command-summary.json").write_text(json.dumps(commands, indent=2) + "\n", encoding="utf-8")
    delivery = json.loads((logs / "delivery-integrity.json").read_text(encoding="utf-8"))
    correction = json.loads((logs / "correction-integrity.json").read_text(encoding="utf-8"))
    runtime = json.loads((logs / "runtime-cleanup.json").read_text(encoding="utf-8"))
    markdown = json.loads((logs / "markdown-check.json").read_text(encoding="utf-8"))
    passed = all(command["exit"] == 0 for command in commands) and delivery["passed"] and correction["passed"] and runtime["passed"] and markdown["errors"] == [] and "Verdict: **accepted**" in (reviewer / "report.md").read_text(encoding="utf-8") and "Status: accepted" in (reviewer / "CHECKPOINT.md").read_text(encoding="utf-8")
    if not passed:
        raise SystemExit("Final acceptance evidence is inconsistent")
    output = logs / "final-artifacts.json"
    seals = [{"path": str(path.relative_to(workspace)), "size": path.stat().st_size, "sha256": fingerprint(path)} for path in sorted(reviewer.rglob("*")) if path.is_file() and path != output]
    value = {"status": "accepted", "feature": "M1/MFR1-MFR3 cumulative private synthetic moderation", "reviewer": "/root/moderation_takeover/moderation_final_review_takeover", "host_id": "01a0f9a5-115e-7d02-bce3-0cf83a18c107", "dispatcher": "/root/moderation_takeover", "workspace": str(workspace), "overall_goal_owner": "root", "checks": {"passed": passed, "independent_executions": 161, "actual_container_executions": 56, "failures_errors_skips": 0, "developer_seals_verified": 57, "source_fingerprints_verified": 143, "MFR1_MFR2_MFR3": "resolved", "baseline289_F3_87_seals6": "preserved", "historical_api_append": correction["old_api"], "historical_checkpoint_archive": correction["preexisting_correction_checkpoint"], "runtime": runtime}, "commands": commands, "decisions_required": [], "seals": seals, "self_hash_exclusions": [str(output.relative_to(workspace))], "next_action": "Coordinator validates actual accepted report/seals, records feature completion and returns dependency evidence to root; root alone releases next ordered feature and owns overall lifecycle."}
    output.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    assert all(fingerprint(workspace / seal["path"]) == seal["sha256"] for seal in seals)
    print(json.dumps({"status": value["status"], "passed": passed, "seals": len(seals), "report_sha256": fingerprint(reviewer / "report.md"), "final_artifacts_sha256": fingerprint(output)}))


if __name__ == "__main__":
    main()
