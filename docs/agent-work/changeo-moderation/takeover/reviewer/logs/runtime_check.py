from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess


def main() -> None:
    logs = Path(__file__).resolve().parent
    commands = [
        ["ps", "-p", "1730365,2094717", "-o", "pid,uid,comm,args"],
        ["ss", "-ltn", "( sport = :55432 or sport = :8080 )"],
        ["curl", "--silent", "--show-error", "--noproxy", "*", "--max-time", "5", "--unix-socket", "/tmp/changeo-podman/podman.sock", "http://localhost/_ping"],
        ["podman", "--root", "/tmp/changeo-podman-store", "--runroot", "/tmp/changeo-podman-run", "ps", "-a", "--format", "json"],
    ]
    results = []
    for command in commands:
        result = subprocess.run(command, check=False, capture_output=True, text=True, timeout=20)
        results.append({"command": command, "exit": result.returncode, "stdout": result.stdout, "stderr": result.stderr})
    passed = all(result["exit"] == 0 for result in results) and "1730365" in results[0]["stdout"] and "2094717" in results[0]["stdout"] and "127.0.0.1:55432" in results[1]["stdout"] and ":8080" not in results[1]["stdout"] and results[2]["stdout"].strip() == "OK" and json.loads(results[3]["stdout"]) == []
    value = {"checked_at_UTC": datetime.now(timezone.utc).isoformat(), "results": results, "passed": passed, "owned_app_browser_started": False, "retained_postgres": 1730365, "retained_podman": 2094717, "owned_containers_remaining": 0}
    (logs / "runtime-cleanup.json").write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(value))
    if not passed:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
