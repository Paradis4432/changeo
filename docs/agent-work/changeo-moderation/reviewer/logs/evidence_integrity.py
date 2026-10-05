import hashlib
import json
from pathlib import Path


def fingerprint(path: Path) -> dict[str, str | int]:
    data = path.read_bytes()
    return {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def inspect(entries: list[dict], root: Path) -> list[dict]:
    results = []
    for entry in entries:
        path = root / entry["path"]
        actual = fingerprint(path) if path.is_file() else None
        results.append({"path": entry["path"], "expected": {key: entry[key] for key in ("size", "sha256")}, "actual": actual, "matches": actual == {key: entry[key] for key in ("size", "sha256")}})
    return results


def main() -> None:
    root = Path.cwd()
    developer = root / "docs/agent-work/changeo-moderation/developer/logs"
    reviewer = root / "docs/agent-work/changeo-moderation/reviewer/logs"
    delivery = json.loads((developer / "final-artifacts.json").read_text(encoding="utf-8"))
    source = json.loads((reviewer / "source-manifest.json").read_text(encoding="utf-8"))
    complete = json.loads((developer / "complete-change-manifest.json").read_text(encoding="utf-8"))
    seals = inspect(delivery["seals"], root)
    sources = inspect(source, root)
    artifacts = inspect(complete["files"], root)
    patches = [{"path": name, "matches": (reviewer / name).read_bytes() == (developer / name).read_bytes(), "reviewer": fingerprint(reviewer / name), "developer": fingerprint(developer / name)} for name in ("cumulative-original-baseline.patch", "accepted-F3.patch", "moderation-after-F3.patch")]
    findings = {"developer_seals": seals, "source_hashes": sources, "developer_complete_manifest": artifacts, "patch_equality": patches, "source_manifest_equal": source == json.loads((developer / "source-manifest.json").read_text(encoding="utf-8")), "app_native_session": {"id": 16306, "exit": 143, "recovered": True}, "counts": {"developer_seals": len(seals), "source_hashes": len(sources), "developer_complete_manifest": len(artifacts)}}
    success = all(item["matches"] for group in (seals, sources, artifacts, patches) for item in group) and findings["source_manifest_equal"]
    findings["passed"] = success
    (reviewer / "evidence-integrity.json").write_text(json.dumps(findings, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"passed": success, "counts": findings["counts"], "patches_equal": all(item["matches"] for item in patches)}))
    raise SystemExit(0 if success else 1)


if __name__ == "__main__":
    main()
