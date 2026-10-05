from pathlib import Path
from datetime import datetime, timezone
import hashlib, json

role = Path("docs/agent-work/changeo-gate0/reviewer/mercadopago")
base = Path("docs/agent-work/changeo-implementation")
approved_path = base / "git-user-approved-baseline.json"
authority_path = base / "originals/user-git-initialization-confirmation.md"
approved = json.loads(approved_path.read_text())
original = json.loads((base / "baseline.json").read_text())
old_seal = json.loads((role / "logs/accepted-artifacts.before-r3g.json").read_text())
errors = []
def meta(path):
    stat = path.stat()
    return {"path": str(path), "mode": oct(stat.st_mode), "bytes": stat.st_size, "modified_utc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()}
expected = approved["current_git_metadata"]
actual = [meta(p) for p in [Path(".git")] + sorted(Path(".git").rglob("*"))]
if actual != expected:
    errors.append("current Git differs from user-approved inventory/modes/sizes/mtimes")
if approved["status"] != "user-approved-initialization-transition" or '"answer":"Yes"' not in authority_path.read_text():
    errors.append("initialization approval evidence")
protected = {}
for p, expected_meta in original["protected_metadata"].items():
    directory = Path(p)
    observed = {"mode": oct(directory.stat().st_mode), "entries": sorted(str(x.relative_to(directory)) for x in directory.rglob("*"))}
    protected[p] = {"original_match": observed == expected_meta}
    if p != ".git" and observed != expected_meta:
        errors.append("original protected metadata mismatch:" + p)
changes_allowed = {str(role / name) for name in ["report.md", "CHECKPOINT.md", "notes.txt"]}
retained = {}
for p, expected_meta in old_seal["artifacts"].items():
    if p in changes_allowed:
        continue
    data = Path(p).read_bytes()
    retained[p] = len(data) == expected_meta["bytes"] and hashlib.sha256(data).hexdigest() == expected_meta["sha256"]
    if not retained[p]:
        errors.append("previously accepted artifact changed:" + p)
archive = json.loads((role / "logs/r3g-archive-first.json").read_text())
archived = json.loads(archive["output"])["prior_small_verdict_seal_copies"]
for p, expected_meta in archived.items():
    data = Path(p).read_bytes()
    if len(data) != expected_meta["bytes"] or hashlib.sha256(data).hexdigest() != expected_meta["sha256"]:
        errors.append("prior verdict/seal archive changed:" + p)
first_failure = Path("docs/agent-work/changeo-gate0/developer/mercadopago/logs/preservation-first.json")
if json.loads(first_failure.read_text())["exit_code"] != 1:
    errors.append("first failure not retained")
print(json.dumps({
    "revision": "R3-G user-approved metadata transition only",
    "authority_original": str(authority_path),
    "approved_observation": str(approved_path),
    "authority_sha256": hashlib.sha256(authority_path.read_bytes()).hexdigest(),
    "approved_observation_sha256": hashlib.sha256(approved_path.read_bytes()).hexdigest(),
    "current_git_metadata": actual,
    "git_inventory_modes_sizes_mtimes_match_approved": actual == expected,
    "original_protected_metadata": protected,
    "original_git_preserved_unchanged": False,
    "git_transition": "User-approved initialization; executing actor not further identified; no metadata mutation performed by this review",
    "other_three_protected_directories_match_original": all(v["original_match"] for p, v in protected.items() if p != ".git"),
    "retained_sealed_artifact_count": len(retained),
    "retained_sealed_artifacts": retained,
    "prior_small_verdict_seal_archive_count": len(archived),
    "first_failed_preservation_exit": 1,
    "errors": errors
}, indent=2))
raise SystemExit(1 if errors else 0)

