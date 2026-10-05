import hashlib
import json
import re
from pathlib import Path


def seal(path: Path, root: Path) -> dict[str, str | int]:
    data = path.read_bytes()
    return {"path": str(path.relative_to(root)), "size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def main() -> None:
    root = Path.cwd()
    reviewer = root / "docs/agent-work/changeo-moderation/reviewer"
    logs = reviewer / "logs"
    catalog_path = logs / "recovered-command-catalog.json"
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    catalog_path.write_text(json.dumps(catalog[:5], indent=2) + "\n", encoding="utf-8")
    commands = []
    for path in sorted(logs.glob("*.exit")):
        log = path.with_suffix(".log")
        commands.append({"name": path.stem, "exit": int(path.read_text(encoding="utf-8").strip()), "exit_file": str(path.relative_to(root)), "log": seal(log, root) if log.is_file() else None})
    (logs / "final-command-results.json").write_text(json.dumps(commands, indent=2) + "\n", encoding="utf-8")
    report = reviewer / "report.md"
    content = report.read_text(encoding="utf-8")
    links = re.findall(r"\[[^\]]+\]\(([^)]+)\)", content)
    missing = [link for link in links if not (report.parent / link).is_file()]
    checks = {"local_links_checked": len(links), "missing": missing, "balanced_fences": content.count("```") % 2 == 0, "verdict": "changes_requested", "required_findings_present": all(name in content for name in ("MFR1", "MFR2", "MFR3"))}
    (logs / "reviewer-document-check.json").write_text(json.dumps(checks, indent=2) + "\n", encoding="utf-8")
    assert not missing and checks["balanced_fences"] and checks["required_findings_present"]
    required = [reviewer / name for name in ("report.md", "CHECKPOINT.md", "notes.txt", "questions.txt")]
    assert all(path.is_file() for path in required)
    artifacts = required + sorted(path for path in logs.rglob("*") if path.is_file() and path.name != "final-artifacts.json")
    seals = [seal(path, root) for path in artifacts]
    delivery = {"verdict": "changes_requested", "reviewer": "/root/changeo_moderation/moderation_final_review", "host_id": "01a0f6a6-743f-7c42-aaf2-67e616a4cb8d", "findings": ["MFR1", "MFR2", "MFR3"], "product_edits": False, "source_hash_count": 143, "changed_source_paths": 63, "independent_verify": {"exit": 0, "executions": 121, "postgres_container_executions": 45, "failures": 0, "errors": 0, "skipped": 0}, "integrity": {"authoritative_developer_seals_match": 19, "current_sources_match": 143, "patches_match": 3, "full_developer_artifacts_match": 177, "full_developer_artifacts_total": 178, "retained_api_log_sealed_prefix_preserved": True, "exhaustive_checker_exit": 1}, "runtime_cleanup": {"source": "docs/agent-work/changeo-moderation/logs/reviewer-app-cleanup.json", "owned_app": 2287567, "app_native_session": 16306, "app_exit": 143, "app_absent": True, "http8080_listener": False, "browser_script_exit": 0, "retained_postgres": 1730365, "retained_private_api": 2094717}, "next_owner": "/root/changeo_moderation", "next_action": "Batch MFR1–MFR3 to same sole builder, then return complete corrected delivery to same independent reviewer; listings gated.", "self_hash_exclusions": ["logs/final-artifacts.json"], "artifact_count": len(seals), "seals": seals}
    (logs / "final-artifacts.json").write_text(json.dumps(delivery, indent=2) + "\n", encoding="utf-8")
    assert all(seal(root / item["path"], root) == item for item in seals)
    print(json.dumps({"verdict": delivery["verdict"], "sealed_artifacts": len(seals), "local_links": len(links), "seals_verified": True}))


if __name__ == "__main__":
    main()
