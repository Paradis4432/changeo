import hashlib
import json
from pathlib import Path

from preservation import fingerprint, patch_text, read_json, save_json


def check_entries(workspace: Path, entries: list[dict]) -> list[dict]:
    return [{**entry, "matches": (workspace / entry["path"]).is_file() and fingerprint(workspace / entry["path"]) == entry["sha256"]} for entry in entries]


def main() -> None:
    workspace = Path(__file__).resolve().parents[6]
    feature = workspace / "docs/agent-work/changeo-moderation"
    delivery = feature / "takeover/developer"
    logs = delivery / "logs"
    started = read_json(logs / "start-source-manifest.json")
    source = check_entries(workspace, started)
    changes = [entry["path"] for entry in source if not entry["matches"]]
    expected_change = "src/test/java/ar/changeo/moderation/ModerationPostgresTest.java"
    (logs / "takeover.patch").write_text(patch_text(set(changes), delivery / "baseline", workspace), encoding="utf-8")
    history = check_entries(workspace, read_json(logs / "start-history-manifest.json"))
    history_changes = [entry["path"] for entry in history if not entry["matches"]]
    save_json(logs / "history1349-integrity.json", history)
    old_seals = {}
    for role in ("developer", "reviewer"):
        old_seals[role] = check_entries(workspace, read_json(feature / role / "logs/final-artifacts.json")["seals"])
    old_manifest = read_json(feature / "developer/logs/complete-change-manifest.json")["files"]
    exhaustive = check_entries(workspace, old_manifest)
    api_name = "docs/agent-work/changeo-moderation/developer/logs/api-start.log"
    api_entry = next(entry for entry in old_manifest if entry["path"] == api_name)
    api = (workspace / api_name).read_bytes()
    start_api = next(entry for entry in history if entry["path"] == api_name)
    api_assessment = {"path": api_name, "sealed_size": api_entry["size"], "takeover_start_size": start_api["size"], "current_size": len(api), "sealed_prefix_matches": hashlib.sha256(api[:api_entry["size"]]).hexdigest() == api_entry["sha256"], "takeover_start_prefix_matches": hashlib.sha256(api[:start_api["size"]]).hexdigest() == start_api["sha256"], "full_file_matches": fingerprint(workspace / api_name) == api_entry["sha256"]}
    archived_checkpoint = feature / "developer/logs/pre-MFR1-MFR3/CHECKPOINT.md"
    checkpoint_seal = next(entry for entry in old_seals["developer"] if entry["path"].endswith("/developer/CHECKPOINT.md"))
    archive_matches = fingerprint(archived_checkpoint) == checkpoint_seal["sha256"]
    sealed_checkpoint_preserved = {"current_path": checkpoint_seal["path"], "current_matches_old_seal": checkpoint_seal["matches"], "archive_path": str(archived_checkpoint.relative_to(workspace)), "archive_matches_old_seal": archive_matches, "current_unchanged_since_takeover": next(entry for entry in history if entry["path"] == checkpoint_seal["path"])["matches"]}
    save_json(logs / "old-artifact-integrity.json", {"authoritative_seals": old_seals, "exhaustive": exhaustive, "api_append_assessment": api_assessment, "preexisting_correction_checkpoint": sealed_checkpoint_preserved})
    summary = {"start_source_count": len(source), "takeover_source_changes": changes, "retained_MFR1_MFR2_sources_unchanged": all(entry["matches"] for entry in source if entry["path"] != expected_change), "start_history_count": len(history), "start_history_changes": history_changes, "old_authoritative_seals_match": all(entry["matches"] for entries in old_seals.values() for entry in entries), "old_exhaustive_matches": sum(entry["matches"] for entry in exhaustive), "old_exhaustive_count": len(exhaustive), "old_exhaustive_mismatches": [entry["path"] for entry in exhaustive if not entry["matches"]], "old_api": api_assessment}
    summary["preexisting_correction_checkpoint"] = sealed_checkpoint_preserved
    preserved_seals = all(entry["matches"] or entry["path"] == checkpoint_seal["path"] and archive_matches for entries in old_seals.values() for entry in entries)
    passed = changes == [expected_change] and history_changes == [api_name] and preserved_seals and api_assessment["sealed_prefix_matches"] and api_assessment["takeover_start_prefix_matches"]
    summary["passed"] = passed
    save_json(logs / "correction-integrity.json", summary)
    print(json.dumps(summary))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
