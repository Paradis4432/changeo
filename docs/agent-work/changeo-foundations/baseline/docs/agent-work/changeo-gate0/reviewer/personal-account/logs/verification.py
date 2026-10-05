from pathlib import Path
from datetime import datetime, timezone
import collections, difflib, hashlib, json, re, urllib.parse

base = Path("docs/agent-work/changeo-implementation")
role = Path("docs/agent-work/changeo-gate0/reviewer/personal-account")
developer = Path("docs/agent-work/changeo-gate0/developer/personal-account")
r4 = json.loads((base / "personal-account-baseline.json").read_text())
r3 = json.loads((base / "mercadopago-baseline.json").read_text())
original = json.loads((base / "baseline.json").read_text())
seal = json.loads(Path(r4["prior_accepted_seal"]).read_text())
allowed = r4["allowed_deliverable_changes"]
errors = []
counts = {}
def hashcheck(path, meta):
    data = Path(path).read_bytes()
    return len(data) == meta["bytes"] and hashlib.sha256(data).hexdigest() == meta["sha256"]
checks = {}
def checked(label, rows):
    rows = list(rows)
    counts[label] = len(rows)
    for p, m in rows:
        ok = hashcheck(p, m)
        checks[str(p)] = ok
        if not ok:
            errors.append("hash:" + str(p))
checked("unaffected_originals", ((p, m) for p, m in original["files"].items() if p not in r3["cumulative_original_source_changes"]))
checked("original_markdown_snapshots", ((base / "source-baseline" / p, m) for p, m in original["files"].items() if p.endswith(".md")))
checked("R3_snapshots", ((base / "mercadopago-baseline" / p, r3["files"][p]) for p in r3["allowed_deliverable_changes"]))
checked("R4_complete_snapshots", ((base / "personal-account-baseline" / p, r4["files"][p]) for p in allowed))
live = {"docs/agent-work/changeo-implementation/GOAL-CHECKPOINT.md", "docs/agent-work/changeo-gate0/CHECKPOINT.md"}
checked("immutable_captured_history", ((p, m) for p, m in r3["files"].items() if p not in set(r3["allowed_deliverable_changes"]) | live))
checked("prior_seal_unaffected", ((p, m) for p, m in seal["artifacts"].items() if p not in allowed))
checked("prior_seal_current_amendment_snapshots", ((base / "personal-account-baseline" / p, seal["artifacts"][p]) for p in allowed))
if len(seal["artifacts"]) != 51 or counts["prior_seal_unaffected"] != 48:
    errors.append("prior51-seal coverage")
protected = {}
for p, expected in original["protected_metadata"].items():
    directory = Path(p)
    observed = {"mode": oct(directory.stat().st_mode), "entries": sorted(str(x.relative_to(directory)) for x in directory.rglob("*"))}
    protected[p] = {"original_match": observed == expected}
    if p != ".git" and observed != expected:
        errors.append("protected:" + p)
approved = json.loads((base / "git-user-approved-baseline.json").read_text())["current_git_metadata"]
observed_git = []
for p in [Path(".git")] + sorted(Path(".git").rglob("*")):
    stat = p.stat()
    observed_git.append({"path": str(p), "mode": oct(stat.st_mode), "bytes": stat.st_size, "modified_utc": datetime.fromtimestamp(stat.st_mtime, timezone.utc).isoformat()})
if observed_git != approved:
    errors.append("approved Git inventory/modes/sizes/times")
patch = []
for p in allowed:
    before = (base / "personal-account-baseline" / p).read_text()
    current = Path(p).read_text()
    patch.extend(difflib.unified_diff(before.splitlines(True), current.splitlines(True), fromfile="pre-R4/" + p, tofile=p))
patch_text = "".join(patch)
(role / "logs/independent-three-file.patch").write_text(patch_text)
if patch_text != (developer / "logs/three-file.patch").read_text() or len(patch) != 99:
    errors.append("exact99-line delta")
links = []
def anchors(path):
    counter = collections.Counter()
    out = set()
    for heading in re.findall(r"^#{1,6}\s+(.+)$", path.read_text(), re.M):
        slug = re.sub(r"[^\w\s-]", "", heading.strip().lower()).replace(" ", "-")
        n = counter[slug]
        counter[slug] += 1
        out.add(slug if n == 0 else f"{slug}-{n}")
    return out
for p in allowed:
    path = Path(p)
    text = path.read_text()
    if path.suffix != ".md":
        continue
    if sum(bool(re.match(r"^\s*```", line)) for line in text.splitlines()) % 2:
        errors.append("fences:" + p)
    for target in re.findall(r"\[[^\]]*\]\(([^)]+)\)", text):
        if re.match(r"^[a-z]+://", target):
            continue
        name, _, fragment = target.partition("#")
        dest = (path.parent / urllib.parse.unquote(name)).resolve() if name else path.resolve()
        ok = dest.is_file() and (not fragment or fragment in anchors(dest))
        links.append({"from": p, "target": target, "valid": ok})
        if not ok:
            errors.append("link:" + p + ":" + target)
before = (base / "personal-account-baseline/docs/gate0-research.md").read_text()
current = Path("docs/gate0-research.md").read_text()
ids = re.findall(r"^\| (G\d{2}\.\d|Y01) \|", current, re.M)
original_question_text = (base / "research-baseline/docs/questions.md").read_text()
original_ids = re.findall(r"\*\*(G\d{2}\.\d):", original_question_text) + ["Y01"]
if ids != original_ids or len(ids) != len(set(ids)) != 25:
    errors.append("original25-ID coverage")
regions = {}
def between(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]
for start, end in [
    ("### Mercado Pago selection and remaining capability — R3", "### Mercado Pago inquiry — prepared, not sent"),
    ("Official public route:", "## Published legal and privacy baselines"),
    ("## Published legal and privacy baselines", "## Original-question coverage"),
    ("## Already settled requirements", "## Original-question coverage"),
    ("| G01.4 |", "\n\n**Resume:")
]:
    regions[start] = between(before, start, end) == between(current, start, end)
    if not regions[start]:
        errors.append("preserved region:" + start)
q = Path("docs/questions.md").read_text()
old_q = (base / "personal-account-baseline/docs/questions.md").read_text()
regions["question R2 budget paragraphs"] = q.split("\n\n")[2:4] == old_q.split("\n\n")[2:4]
regions["question final gate paragraphs"] = q.split("The [complete original-ID map]", 1)[1] == old_q.split("The [complete original-ID map]", 1)[1]
if not all(regions.values()):
    errors.append("question unchanged-region check")
dev_logs = {}
for name in ["baseline-before.json", "preservation-first.json", "document-first.json", "document-final.json", "document-delivery.json", "artifact-check.json"]:
    d = json.loads((developer / "logs" / name).read_text())
    dev_logs[name] = d["exit_code"]
    if d["exit_code"] != 0:
        errors.append("developer exit:" + name)
d = json.loads(json.loads((developer / "logs/artifact-check.json").read_text())["output"])
for p, expected in d["artifact_hashes"].items():
    if hashlib.sha256((developer / p).read_bytes()).hexdigest() != expected:
        errors.append("developer artifact hash:" + p)
for p, expected in d["current_deliverables"].items():
    if not hashcheck(p, expected):
        errors.append("developer deliverable hash:" + p)
terms = json.loads((base / "logs/personal-account-root-primary-check.json").read_text())
if not isinstance(terms, str) or "(403) Forbidden" not in terms or "2.1 Licencia" not in terms or "2.4 Aplicaciones" not in terms:
    errors.append("actual reused terms/access evidence")
print(json.dumps({
    "scope": "R4 three-file documentation clarification only",
    "counts": counts, "hash_checks": checks, "live_checkpoint_owners_excluded": sorted(live),
    "protected_metadata": protected, "approved_git_entries": len(observed_git),
    "approved_git_inventory_modes_sizes_times_match": observed_git == approved,
    "original_git_unchanged": False, "git_transition_authority": "user approved; original baseline/failure retained",
    "exact_independent_patch_lines": len(patch), "local_link_count": len(links), "links": links,
    "original25_IDs": ids, "unchanged_regions": regions, "developer_check_exits": dev_logs,
    "reused_terms_access_limit": "General account terms403; actual developer API licence/responsibility clauses not custody approval",
    "errors": errors
}, indent=2, ensure_ascii=False))
raise SystemExit(bool(errors))

