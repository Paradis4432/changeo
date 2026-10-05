import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def fingerprint(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(path: Path) -> object:
    return json.loads(path.read_text(encoding="utf-8"))


def save(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    workspace = Path(__file__).resolve().parents[6]
    feature = workspace / "docs/agent-work/changeo-moderation"
    logs = feature / "takeover/reviewer/logs"
    delivered = feature / "takeover/developer/logs"
    artifacts = load(delivered / "final-artifacts.json")
    seals = [{**item, "matches": (workspace / item["path"]).is_file() and fingerprint(workspace / item["path"]) == item["sha256"]} for item in artifacts["seals"]]
    sources = [{**item, "matches": (workspace / item["path"]).is_file() and fingerprint(workspace / item["path"]) == item["sha256"]} for item in load(delivered / "source-manifest.json")]
    patches = [{"name": name, "sha256": fingerprint(logs / name), "matches": (logs / name).read_bytes() == (delivered / name).read_bytes()} for name in ("cumulative-original-baseline.patch", "accepted-F3.patch", "moderation-after-F3.patch", "takeover.patch")]
    old_sources = load(feature / "reviewer/logs/source-manifest.json")
    changed = [item["path"] for item in old_sources if fingerprint(workspace / item["path"]) != item["sha256"]]
    suites = []
    for kind in ("surefire", "failsafe"):
        for path in sorted((workspace / "target" / f"{kind}-reports").glob("TEST-*.xml")):
            root = ET.parse(path).getroot()
            cases = [{"name": case.attrib["name"], "classname": case.attrib["classname"], "failure": case.find("failure") is not None, "error": case.find("error") is not None, "skipped": case.find("skipped") is not None} for case in root.findall("testcase")]
            suites.append({"kind": kind, "path": str(path.relative_to(workspace)), "sha256": fingerprint(path), "suite": root.attrib["name"], **{key: int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}, "cases": cases})
    totals = {key: sum(suite[key] for suite in suites) for key in ("tests", "failures", "errors", "skipped")}
    mfr3 = {kind: sum("AccessWithdrawal" in case["name"] for suite in suites if suite["kind"] == kind for case in suite["cases"]) for kind in ("surefire", "failsafe")}
    mfr12_names = ("shouldRecoverApprovedActivationAfterTransientRollbackWithExactlyOnePublication", "shouldExhaustActivationOutagesAndRecoverThroughOneCurrentGuardedRetryGeneration", "shouldPersistNonpositiveInvalidDecisionForExactlyBoundContradictoryAdapterOutput")
    mfr12 = {kind: [name for name in mfr12_names if any(case["name"].startswith(name) for suite in suites if suite["kind"] == kind for case in suite["cases"])] for kind in ("surefire", "failsafe")}
    suite_summary = {"totals": totals, "by_kind": {kind: sum(suite["tests"] for suite in suites if suite["kind"] == kind) for kind in ("surefire", "failsafe")}, "MFR3_executions": mfr3, "MFR1_MFR2_methods": mfr12, "suites": suites, "private_exclusions": "XML properties/stdout/stderr not copied; only testcase names/results and file fingerprints saved."}
    save(logs / "verification-summary.json", suite_summary)
    expected_changes = {"src/main/java/ar/changeo/moderation/ModerationWorker.java", "src/main/java/ar/changeo/moderation/ReviewResult.java", "src/test/java/ar/changeo/moderation/ModerationPostgresTest.java", "src/test/java/ar/changeo/moderation/ReviewValidationTest.java"}
    passed = all(item["matches"] for item in seals + sources + patches) and set(changed) == expected_changes and totals == {"tests": 161, "failures": 0, "errors": 0, "skipped": 0} and mfr3 == {"surefire": 8, "failsafe": 8} and all(len(names) == 3 for names in mfr12.values())
    summary = {"passed": passed, "developer_seals": seals, "current_sources": sources, "patch_comparisons": patches, "historical_review_source_changes": changed, "totals": totals, "actual_container_executions": suite_summary["by_kind"]["failsafe"], "MFR3_executions": mfr3, "MFR1_MFR2_methods": mfr12}
    save(logs / "delivery-integrity.json", summary)
    print(json.dumps({"passed": passed, "seals": len(seals), "sources": len(sources), "patches": patches, "historical_source_changes": changed, "totals": totals, "container": suite_summary["by_kind"]["failsafe"], "MFR3": mfr3, "MFR1_MFR2": mfr12}))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
