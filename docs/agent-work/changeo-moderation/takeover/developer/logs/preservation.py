import difflib
import hashlib
import json
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote


def fingerprint(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def save_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def source_paths(workspace: Path) -> set[str]:
    paths = {str(path.relative_to(workspace)) for folder in ("src", "scripts", ".mvn") for path in (workspace / folder).rglob("*") if path.is_file()}
    paths.update(str(path.relative_to(workspace)) for path in (workspace / "docs").glob("*.md"))
    paths.update(name for name in (".gitignore", "pom.xml", "mvnw", "mvnw.cmd") if (workspace / name).is_file())
    return paths


def patch_text(paths: set[str], old_root: Path, workspace: Path) -> str:
    result = []
    for name in sorted(paths):
        old, new = old_root / name, workspace / name
        before = old.read_text(encoding="utf-8").splitlines(keepends=True) if old.is_file() else []
        after = new.read_text(encoding="utf-8").splitlines(keepends=True) if new.is_file() else []
        result.extend(difflib.unified_diff(before, after, fromfile="a/" + name if old.is_file() else "/dev/null", tofile="b/" + name if new.is_file() else "/dev/null"))
    return "".join(result)


def anchors(text: str) -> set[str]:
    result = set(re.findall(r'<a\s+(?:id|name)="([^"]+)"', text))
    counts: dict[str, int] = {}
    for heading in re.findall(r"^#{1,6}\s+(.+)$", text, re.MULTILINE):
        slug = re.sub(r"[^\w\- ]", "", heading.lower()).replace(" ", "-")
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        result.add(slug if count == 0 else f"{slug}-{count}")
    return result


def markdown_checks(workspace: Path, paths: list[str]) -> dict:
    errors = []
    links = 0
    for name in paths:
        path = workspace / name
        text = path.read_text(encoding="utf-8")
        fence = None
        for line in text.splitlines():
            match = re.match(r"^\s*(`{3,}|~{3,})", line)
            if match is not None:
                marker = match.group(1)
                if fence is None:
                    fence = marker
                elif marker[0] == fence[0] and len(marker) >= len(fence):
                    fence = None
        if fence is not None:
            errors.append({"path": name, "error": "unclosed fence"})
        for target in re.findall(r"\]\(([^)]+)\)", text):
            target = target.strip().strip("<>")
            if re.match(r"[a-z]+:", target):
                continue
            links += 1
            file_name, _, anchor = unquote(target).partition("#")
            destination = path.parent / file_name if file_name else path
            if not destination.is_file():
                errors.append({"path": name, "target": target, "error": "missing file"})
            elif anchor and destination.suffix == ".md" and anchor not in anchors(destination.read_text(encoding="utf-8")):
                errors.append({"path": name, "target": target, "error": "missing anchor"})
    return {"paths": paths, "local_links_checked": links, "errors": errors}


def question_ids(text: str) -> list[str]:
    return sorted(set(re.findall(r"\b(?:G0[1-6]\.\d+|Y01)\b", text)))


def main() -> None:
    workspace = Path(__file__).resolve().parents[6]
    feature = workspace / "docs/agent-work/changeo-moderation"
    logs = feature / "takeover/developer/logs"
    baseline = read_json(feature / "baseline.json")
    supplement = read_json(feature / "dependency-F3.json")
    original = feature / "baseline"
    dependency = feature / "dependency-F3/source"
    baseline_integrity = [{**entry, "snapshot_matches": fingerprint(original / entry["path"]) == entry["sha256"]} for entry in baseline["manifest"]]
    f3_integrity = [{**entry, "snapshot_matches": fingerprint(workspace / entry["source"]) == entry["sha256"], "current_matches": fingerprint(workspace / entry["path"]) == entry["sha256"]} for entry in supplement["source87"]]
    seals = [{**entry, "snapshot_matches": fingerprint(workspace / entry["snapshot"]) == entry["sha256"], "current_matches": fingerprint(workspace / entry["path"]) == entry["sha256"]} for entry in supplement["evidence"]]
    protected = {}
    for name, expected in baseline["protected_names_only"].items():
        path = workspace / name
        current = {"exists": path.exists(), "symlink": path.is_symlink(), "entries": sorted(str(item.relative_to(path)) for item in path.rglob("*"))}
        protected[name] = {"expected": expected, "current": current, "matches": current == expected}
    paths = source_paths(workspace) - {"docs/pending-tasks.md"}
    prompt = workspace / "prompt.md"
    handoff = read_json(workspace / "docs/agent-work/changeo-implementation/logs/pending-tasks-handoff-check.json")
    handoff_preserved = fingerprint(workspace / handoff["file"]) == handoff["sha256"]
    save_json(logs / "unowned-inputs.json", {"prompt.md": {"sha256": fingerprint(prompt), "present_in_original_git_inventory": "?? prompt.md\n" in baseline["git"]["status"]["stdout"], "baseline_content_snapshot": False, "attribution": "Pre-existing user input outside assigned product source paths; not included as a moderation addition or edited by this worker."}, "docs/pending-tasks.md": {"sha256": fingerprint(workspace / handoff["file"]), "root_handoff_hash_matches": handoff_preserved, "root_evidence": "docs/agent-work/changeo-implementation/logs/pending-tasks-handoff-check.json", "attribution": "Root-authored index added after the original moderation baseline and outside developer ownership; excluded from moderation patch, retained by exact root hash."}})
    manifest = [{"path": name, "size": (workspace / name).stat().st_size, "sha256": fingerprint(workspace / name)} for name in sorted(paths)]
    changed = []
    for entry in manifest:
        name = entry["path"]
        prior = dependency / name if (dependency / name).is_file() else original / name
        if not prior.is_file() or fingerprint(prior) != entry["sha256"]:
            changed.append({**entry, "moderation_change": "modified" if prior.is_file() else "added", "before_sha256": fingerprint(prior) if prior.is_file() else None})
    integrations = {"src/main/resources/application.properties", "src/main/resources/templates/shell.html", "src/main/resources/templates/home.html", "src/main/resources/static/style.css", "src/main/java/ar/changeo/security/SecurityConfig.java", "src/main/java/ar/changeo/config/ModerationSchedule.java"}
    docs = {"docs/" + name + ".md" for name in ("README", "architecture", "delivery", "trust-operations", "payments", "questions", "sandbox-runtime")}
    prefixes = ("src/main/java/ar/changeo/moderation/", "src/main/java/ar/changeo/files/", "src/test/java/ar/changeo/moderation/", "src/test/java/ar/changeo/files/", "scripts/moderation-", "src/main/resources/templates/moderation-", "src/main/resources/templates/content-")
    unauthorized = [entry["path"] for entry in changed if not (entry["path"] in integrations | docs or entry["path"].startswith(prefixes) or re.fullmatch(r"src/main/resources/db/migration/V[4-9].*", entry["path"]))]
    foundation = [entry for entry in f3_integrity if entry["path"].startswith(("src/main/java/ar/changeo/identity/", "src/test/java/ar/changeo/identity/", "src/main/resources/db/migration/V1", "src/main/resources/db/migration/V2", "src/main/resources/db/migration/V3"))]
    others = [entry["path"] for entry in baseline["manifest"] if entry["path"] not in paths and (not (workspace / entry["path"]).is_file() or fingerprint(workspace / entry["path"]) != entry["sha256"])]
    f3_paths = {entry["path"] for entry in supplement["source87"]}
    (logs / "cumulative-original-baseline.patch").write_text(patch_text(paths, original, workspace), encoding="utf-8")
    (logs / "accepted-F3.patch").write_text(patch_text(f3_paths, original, dependency), encoding="utf-8")
    (logs / "moderation-after-F3.patch").write_text("".join(patch_text({name}, dependency if (dependency / name).is_file() else original, workspace) for name in sorted(paths)), encoding="utf-8")
    save_json(logs / "source-manifest.json", manifest)
    save_json(logs / "changed-paths.json", changed)
    save_json(logs / "baseline289-integrity.json", baseline_integrity)
    save_json(logs / "F3-integrity.json", {"source87": f3_integrity, "seals6": seals, "accepted_additions": supplement["changes_from_original_moderation_baseline"]})
    history_paths = ["docs/gate0-research.md", "docs/agent-work/changeo-planning/requirements.md", "docs/agent-work/changeo-planning/CHECKPOINT.md"]
    history = [{"path": name, "preserved": fingerprint(original / name) == fingerprint(workspace / name)} for name in history_paths]
    baseline_ids = question_ids((original / "docs/gate0-research.md").read_text(encoding="utf-8"))
    current_ids = question_ids((workspace / "docs/gate0-research.md").read_text(encoding="utf-8"))
    questions = (workspace / "docs/questions.md").read_text(encoding="utf-8")
    history_result = {"history": history, "original_question_ids": baseline_ids, "current_question_ids": current_ids, "ids_preserved": baseline_ids == current_ids, "mandatory_count": len([value for value in current_ids if value != "Y01"]), "Gate0_remains_open": "All six" in questions and "remain open" in questions, "production_and_real_money_unapproved": "production" in questions and "real money remain unapproved" in questions}
    save_json(logs / "history-question-preservation.json", history_result)
    markdown = markdown_checks(workspace, sorted(docs | {"docs/agent-work/changeo-moderation/takeover/developer/report.md"}))
    save_json(logs / "markdown-check.json", markdown)
    commands = []
    for exit_file in sorted(logs.glob("*.exit")):
        log = exit_file.with_suffix(".log")
        commands.append({"name": exit_file.stem, "exit": int(exit_file.read_text(encoding="utf-8").strip()), "exit_file": str(exit_file.relative_to(workspace)), "log": str(log.relative_to(workspace)) if log.is_file() else None, "log_sha256": fingerprint(log) if log.is_file() else None})
    save_json(logs / "command-summary.json", commands)
    git = {}
    for name, args in {"branch": ["symbolic-ref", "--short", "HEAD"], "head": ["rev-parse", "--verify", "HEAD"], "staged": ["diff", "--cached", "--stat"]}.items():
        result = subprocess.run(["git", *args], cwd=workspace, capture_output=True, text=True, check=False)
        git[name] = {"exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    summary = {"baseline_snapshot_count": len(baseline_integrity), "baseline_snapshots_preserved": all(entry["snapshot_matches"] for entry in baseline_integrity), "F3_snapshot_count": len(f3_integrity), "F3_snapshots_preserved": all(entry["snapshot_matches"] for entry in f3_integrity), "F3_seals_preserved": all(entry["snapshot_matches"] and entry["current_matches"] for entry in seals), "foundation_identity_tests_V1_V3_preserved": all(entry["current_matches"] for entry in foundation), "F3_current_differences": [entry["path"] for entry in f3_integrity if not entry["current_matches"]], "protected": protected, "changed_source_paths": len(changed), "unauthorized_source_paths": unauthorized, "other_current_baseline_changes": others, "history_questions": history_result, "markdown": markdown, "git": git, "private_exclusions": [".runtime", ".local-tools", "target", "protected metadata contents"], "runtime_cleanup": "runtime-cleanup.json"}
    save_json(logs / "preservation-summary.json", summary)
    errors = unauthorized or markdown["errors"] or not handoff_preserved or not all(entry["matches"] for entry in protected.values()) or not all(entry["snapshot_matches"] for entry in baseline_integrity + f3_integrity) or not all(entry["snapshot_matches"] and entry["current_matches"] for entry in seals) or not all(entry["current_matches"] for entry in foundation) or not history_result["ids_preserved"] or not all(entry["preserved"] for entry in history) or not history_result["Gate0_remains_open"]
    print(json.dumps({"baseline": len(baseline_integrity), "F3": len(f3_integrity), "seals": len(seals), "source_changes": len(changed), "unauthorized": unauthorized, "markdown_errors": markdown["errors"], "other_baseline_changes": others, "passed": not bool(errors)}))
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
