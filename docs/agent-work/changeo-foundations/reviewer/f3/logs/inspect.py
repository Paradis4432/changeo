from pathlib import Path
import difflib
import hashlib
import json
import re
import subprocess
import xml.etree.ElementTree as ET

root = Path.cwd()
foundation = root / 'docs/agent-work/changeo-foundations'
out = foundation / 'reviewer/f3/logs'
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
baseline = json.loads((foundation / 'f3/baseline.json').read_text())
original = json.loads((foundation / 'baseline.json').read_text())
manifest = json.loads((foundation / 'developer/f3/logs/changed-file-manifest.json').read_text())
delta = json.loads((foundation / 'developer/f3/logs/delta-manifest.json').read_text())
errors = []
current_manifest = []
for f in manifest:
    p = root / f['path']
    current = dict(path=f['path'], size=p.stat().st_size, sha256=sha(p))
    current_manifest.append(current)
    if current != f:
        errors.append('Current manifest mismatch: ' + f['path'])
changes = []
for f in baseline['files']:
    snapshot = foundation / 'f3/baseline' / f['path']
    if sha(snapshot) != f['sha256']:
        errors.append('Amendment snapshot mismatch: ' + f['path'])
    now = sha(root / f['path'])
    if now != f['sha256']:
        changes.append(dict(path=f['path'], before=f['sha256'], after=now))
existing = {f['path'] for f in baseline['files']}
for f in manifest:
    if f['path'] not in existing:
        changes.append(dict(path=f['path'], before=None, after=sha(root / f['path'])))
if changes != delta:
    errors.append('Actual delta differs from delivered delta')
patch = ''
for f in changes:
    before = (foundation / 'f3/baseline' / f['path']).read_text().splitlines(keepends=True) if f['before'] else []
    after = (root / f['path']).read_text().splitlines(keepends=True)
    patch += ''.join(difflib.unified_diff(before, after, fromfile='before/' + f['path'] if f['before'] else '/dev/null', tofile='after/' + f['path']))
if patch != (foundation / 'developer/f3/logs/f3.patch').read_text():
    errors.append('Regenerated patch differs')
(out / 'inspected-f3.patch').write_text(patch)
original_differences = []
for f in original['files']:
    if sha(foundation / 'baseline' / f['path']) != f['sha256']:
        errors.append('Original snapshot mismatch: ' + f['path'])
    if sha(root / f['path']) != f['sha256']:
        original_differences.append(f['path'])
history = []
for p in (foundation / 'f3/baseline-artifacts').rglob('*'):
    if p.is_file():
        rel = p.relative_to(foundation / 'f3/baseline-artifacts')
        live = foundation / rel
        matches = live.exists() and sha(p) == sha(live)
        history.append(dict(path=str(rel), snapshot_sha256=sha(p), matches=matches))
        if not matches and str(rel) not in ['CHECKPOINT.md', 'coordinator-report.md']:
            errors.append('Historical artifact changed: ' + str(rel))
retained = []
for name in ['IdentityPostgresTest.java', 'WebsiteSecurityTest.java', 'PostgresContainerIT.java']:
    rel = 'src/test/java/ar/changeo/identity/' + name
    old = (foundation / 'f3/baseline' / rel).read_text()
    new = (root / rel).read_text()
    names = re.findall(r'void (should\w+)\(', old)
    for name in names:
        if not re.search(r'void ' + name + r'\(', new):
            errors.append('Old test lost: ' + name)
    retained.append(dict(path=rel, original_methods=names, retained=True))
suites = []
for folder in ['surefire-reports', 'failsafe-reports']:
    for p in (root / 'target' / folder).glob('TEST-*.xml'):
        suite = ET.parse(p).getroot()
        suites.append({k:suite.attrib[k] for k in ['name','tests','failures','errors','skipped']})
total = sum(int(s['tests']) for s in suites)
if total != 68 or any(int(s[k]) for s in suites for k in ['failures', 'errors', 'skipped']):
    errors.append('Unexpected test result')
source_paths = {str(p.relative_to(root)) for folder in ['src','scripts','.mvn'] for p in (root / folder).rglob('*') if p.is_file()}
known_paths = {f['path'] for f in manifest}
unexpected_sources = sorted(source_paths - known_paths)
if unexpected_sources:
    errors.append('Unexpected source additions')
git = {}
for c in [['git','diff','--binary'], ['git','diff','--cached','--binary'], ['git','branch','--show-current'], ['git','rev-parse','--verify','HEAD'], ['git','status','--porcelain=v1','--untracked-files=all']]:
    r = subprocess.run(c, capture_output=True, text=True)
    git[' '.join(c)] = dict(exit=r.returncode, stdout=r.stdout, stderr=r.stderr)
captured_git = {c:dict(exit=r['exit'], stdout_lines=len(r['stdout'].splitlines()), stdout_sha256=hashlib.sha256(r['stdout'].encode()).hexdigest(), stderr=r.get('stderr','')) for c,r in baseline['commands'].items()}
read_paths = ['docs/agent-work/changeo-implementation/foundations-extension-F3-brief.md', 'docs/agent-work/changeo-implementation/moderation-contract-M1.md', 'docs/agent-work/changeo-moderation/shared-contract-proposal-M1.md', 'docs/agent-work/changeo-foundations/f3/plan.md']
read_sources = [dict(path=p, sha256=sha(root / p)) for p in read_paths]
result = dict(exit=0 if not errors else 1, errors=errors, current_manifest_files=len(manifest), amendment_snapshots=len(baseline['files']), original_snapshots=len(original['files']), unchanged_accepted_files=len(baseline['files'])-sum(c['before'] is not None for c in changes), delta=changes, original_current_differences=original_differences, historical_artifacts=history, retained_test_methods=retained, suites=suites, total_tests=total, unexpected_sources=unexpected_sources, read_sources=read_sources, captured_baseline_command_metadata=captured_git, git=git, v1_v2_unchanged=all(sha(root / p)==sha(foundation / 'f3/baseline' / p) for p in ['src/main/resources/db/migration/V1__identity.sql','src/main/resources/db/migration/V2__identity_state_integrity.sql']))
(out / 'source-manifest.json').write_text(json.dumps(current_manifest,indent=2)+'\n')
(out / 'inspection.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps({k:result[k] for k in ['exit','errors','current_manifest_files','amendment_snapshots','original_snapshots','unchanged_accepted_files','total_tests','unexpected_sources','v1_v2_unchanged']},indent=2))
raise SystemExit(result['exit'])
