from pathlib import Path
import collections, difflib, hashlib, json, re, sys, urllib.parse

root = Path.cwd()
owned = Path("docs/agent-work/changeo-gate0/reviewer/mercadopago")
base = Path("docs/agent-work/changeo-implementation")
r3 = json.loads((base / "mercadopago-baseline.json").read_text())
original = json.loads((base / "baseline.json").read_text())
allowed = r3["allowed_deliverable_changes"]
cumulative = set(r3["cumulative_original_source_changes"])
live = {"docs/agent-work/changeo-implementation/GOAL-CHECKPOINT.md", "docs/agent-work/changeo-gate0/CHECKPOINT.md"}
scoped = "--scoped" in sys.argv
errors = []
def digest(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def check_hash(path, metadata):
    p = Path(path)
    return p.is_file() and p.stat().st_size == metadata["bytes"] and digest(p) == metadata["sha256"]
unaffected = {p: check_hash(p, m) for p, m in original["files"].items() if p not in cumulative}
immutable = {p: check_hash(p, m) for p, m in r3["files"].items() if p not in allowed and p not in live}
errors += [p for p, ok in {**unaffected, **immutable}.items() if not ok]
snapshots = {}
patch = []
for p in allowed:
    snap = base / "mercadopago-baseline" / p
    snapshots[str(snap)] = check_hash(snap, r3["files"][p])
    old, new = snap.read_text(), Path(p).read_text()
    patch.extend(difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True), fromfile="pre-R3/" + p, tofile=p))
errors += [p for p, ok in snapshots.items() if not ok]
patch_text = "".join(patch)
(owned / "logs/independent-seven-file.patch").write_text(patch_text)
if patch_text != Path("docs/agent-work/changeo-gate0/developer/mercadopago/logs/seven-file.patch").read_text():
    errors.append("developer patch differs from independently generated complete delta")
cumulative_patch = []
for p in sorted(cumulative):
    old = (base / "source-baseline" / p).read_text()
    new = Path(p).read_text()
    cumulative_patch.extend(difflib.unified_diff(old.splitlines(keepends=True), new.splitlines(keepends=True), fromfile="original/" + p, tofile=p))
(owned / "logs/cumulative-source.patch").write_text("".join(cumulative_patch))
protected = {}
for p, expected in original["protected_metadata"].items():
    directory = Path(p)
    actual = {"mode": oct(directory.stat().st_mode), "entries": sorted(str(x.relative_to(directory)) for x in directory.rglob("*"))}
    same = actual == expected
    protected[p] = {"original_match": same, "current": actual}
    if not same and (p != ".git" or not scoped):
        errors.append("protected original mismatch:" + p)
git_observed = json.loads((base / "logs/git-metadata-change.json").read_text())["current_git_metadata"]
git_current = [{"path": str(p), "mode": oct(p.stat().st_mode), "bytes": p.stat().st_size} for p in [Path(".git")] + sorted(Path(".git").rglob("*"))]
git_expected = [{"path": p["path"], "mode": p["mode"], "bytes": p["bytes"]} for p in git_observed]
if git_current != git_expected:
    errors.append("current Git inventory/mode/size differs from root observation")
fences = {}
links = []
def anchors(path):
    counts = collections.Counter()
    results = set()
    for heading in re.findall(r"^#{1,6}\s+(.+)$", path.read_text(), re.M):
        slug = re.sub(r"[^\w\s-]", "", heading.strip().lower()).replace(" ", "-")
        suffix = counts[slug]
        counts[slug] += 1
        results.add(slug if suffix == 0 else f"{slug}-{suffix}")
    return results
for p in allowed:
    path = Path(p)
    body = path.read_text()
    if path.suffix != ".md":
        continue
    fences[p] = sum(bool(re.match(r"^\s*```", line)) for line in body.splitlines()) % 2 == 0
    if not fences[p]:
        errors.append("unbalanced fences:" + p)
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", body):
        if re.match(r"^[a-z]+://", target):
            continue
        dest, _, fragment = target.partition("#")
        location = (path.parent / urllib.parse.unquote(dest)).resolve() if dest else path.resolve()
        valid = location.is_file() and (not fragment or fragment in anchors(location))
        links.append({"from": p, "target": target, "valid": valid})
        if not valid:
            errors.append("link:" + p + ":" + target)
current_research = Path("docs/gate0-research.md").read_text()
before_research = (base / "mercadopago-baseline/docs/gate0-research.md").read_text()
ids = re.findall(r"^\| (G\d{2}\.\d|Y01) \|", current_research, re.M)
original_questions = (base / "research-baseline/docs/questions.md").read_text()
prior_ids = re.findall(r"\*\*(G\d{2}\.\d):", original_questions)
if "**Y01 (unanswered, optional):**" in original_questions:
    prior_ids.append("Y01")
if ids != prior_ids or len(ids) != 25 or len(set(ids)) != 25:
    errors.append("original 25-ID coverage")
contract_tails = {}
for p, marker in [("docs/payments.md", "## Agreement and release contract"), ("docs/architecture.md", "## Core records and behavior contracts"), ("docs/business.md", "## Subscription behavior")]:
    same = Path(p).read_text().split(marker, 1)[1] == (base / "mercadopago-baseline" / p).read_text().split(marker, 1)[1]
    contract_tails[p] = same
    if not same:
        errors.append("contract tail:" + p)
r2marker = "### Supplied budget and solo support — R2\n"
def r2(text):
    return text.split(r2marker, 1)[1].split("\n## Already settled requirements", 1)[0]
current_r2 = r2(current_research).replace("No runtime/trust provider is selected by this comparison; R3 separately selects Mercado Pago for payments.", "No new provider is selected.")
r2_same = current_r2 == r2(before_research)
if not r2_same:
    errors.append("R2 budget/support region differs beyond selection clarification")
seals = {}
for round_name in ["budget", "research"]:
    sealed = json.loads(Path(f"docs/agent-work/changeo-gate0/reviewer/{round_name}/logs/accepted-artifacts.json").read_text())["artifacts"]
    rows = {}
    for p, m in sealed.items():
        if p in allowed:
            candidate = base / ("mercadopago-baseline" if round_name == "budget" else "budget-baseline") / p
        else:
            candidate = Path(p)
        rows[str(candidate)] = check_hash(candidate, m)
    seals[round_name] = rows
    errors += ["historical seal:" + p for p, ok in rows.items() if not ok]
developer = Path("docs/agent-work/changeo-gate0/developer/mercadopago")
developer_logs = {}
for name, expected in [("baseline-before.json", 0), ("preservation-first.json", 1), ("document-first.json", 0), ("history-check.json", 0), ("artifact-check.json", 0)]:
    d = json.loads((developer / "logs" / name).read_text())
    observed = d.get("exit_code", d.get("exit_status"))
    developer_logs[name] = {"exit": observed, "expected": expected, "sha256": digest(developer / "logs" / name)}
    if observed != expected:
        errors.append("developer exit:" + name)
artifact_log = json.loads((developer / "logs/artifact-check.json").read_text())
artifact_rows = json.loads(artifact_log["output"])["artifacts"]
dev_artifacts = {}
for item in artifact_rows:
    p = item["path"]
    same = check_hash(p, item)
    dev_artifacts[p] = {"matches_pre_report_row_addition": same, "current_sha256": digest(p)}
    if not same and p != str(developer / "report.md"):
        errors.append("developer artifact drift:" + p)
manifest = json.loads((developer / "logs/sources-manifest.json").read_text())
source_evidence = {}
for item in manifest["sources"]:
    evidences = item["evidence"] if isinstance(item["evidence"], list) else [item["evidence"]]
    for p in evidences:
        path = Path(p) if p.startswith("docs/") else developer / "logs" / p
        source_evidence[str(path)] = path.is_file()
        if not path.is_file():
            errors.append("source evidence:" + str(path))
result = {
    "scope": "R3 documentation only; no Gate0/account/legal/runtime acceptance",
    "mode": "scoped with original Git EXCEPTION/UNVERIFIED" if scoped else "first complete original-preservation audit",
    "unaffected_original_count": len(unaffected), "unaffected_originals": unaffected,
    "captured_immutable_count": len(immutable), "captured_immutable": immutable,
    "live_checkpoint_drift_owned": sorted(live), "snapshots": snapshots,
    "protected_metadata": protected,
    "original_git_preservation": "EXCEPTION/UNVERIFIED: initialized from original empty mode0o40555; actor/authority UNKNOWN; never counted as preserved",
    "current_git_matches_root_inventory_mode_size": git_current == git_expected,
    "patch_lines": len(patch), "cumulative_original_source_patch_lines": len(cumulative_patch),
    "local_link_count": len(links), "local_links": links, "balanced_fences": fences,
    "original_ids": ids, "contract_tails": contract_tails, "R2_region_preserved_except_selection": r2_same,
    "historical_seal_checks": seals, "developer_first_run_exits": developer_logs,
    "developer_artifacts": dev_artifacts, "primary_source_evidence": source_evidence,
    "errors": errors
}
print(json.dumps(result, indent=2, ensure_ascii=False))
raise SystemExit(1 if errors else 0)
