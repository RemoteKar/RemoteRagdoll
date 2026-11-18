"""Run NativeSmoke against a shaded RemoteRagdoll jar using an existing Java 25 JDK.

This checks JNI packaging and standalone physics, not Bukkit or in-game behavior.
Usage: python validation/run_native_smoke.py --jar build/libs/RemoteRagdoll-1.0.0.jar
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))


def run_logged(command, log_path):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    log_path.write_bytes(result.stdout)
    return result.returncode, result.stdout.decode("utf-8", errors="replace")


def main():
    project = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, default=Path(os.environ.get(
        "JAVA_HOME", str(JDK))))
    parser.add_argument("--output", type=Path, default=project.parents[1] / "output/RemoteRagdoll/validation")
    args = parser.parse_args()
    jar = args.jar.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    classes = project / "build/validation/native-smoke/classes"
    classes.mkdir(parents=True, exist_ok=True)
    java = args.java_home / "bin/java.exe"
    javac = args.java_home / "bin/javac.exe"
    code, compiler_text = run_logged([
        str(javac), "--release", "25", "-cp", str(jar), "-d", str(classes),
        str(project / "validation/NativeSmoke.java"),
    ], output / "native-compile.log")
    if code:
        raise RuntimeError(f"javac exited {code}:\n{compiler_text}")

    results = []
    for name, options, expected_code in [
        ("default", [], 0),
        ("enabled", ["--enable-native-access=ALL-UNNAMED"], 0),
        ("denied", ["--illegal-native-access=deny"], 1),
    ]:
        command = [str(java), *options, "-ea", "-cp", f"{classes}{os.pathsep}{jar}",
                   "NativeSmoke", str(output / f"native-{name}")]
        code, text = run_logged(command, output / f"native-{name}.log")
        passed = (code == expected_code)
        if name == "default":
            passed &= "PASS: native load" in text and "WARNING:" in text
        elif name == "enabled":
            passed &= "PASS: native load" in text and "WARNING:" not in text
        else:
            passed &= "IllegalCallerException" in text and "PASS: native load" not in text
        result = {"mode": name, "command": command, "exitCode": code,
                  "expectedExitCode": expected_code, "passed": passed,
                  "log": str(output / f"native-{name}.log")}
        results.append(result)
        print(f"{name}: {'PASS' if passed else 'FAIL'} (exit {code})")

    summary = {
        "scope": "Standalone Windows JNI; Bukkit, ModelEngine, Skript and Linux execution untested",
        "jar": str(jar), "jarSha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
        "javaHome": str(args.java_home), "runs": results,
    }
    (output / "native-summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    if not all(result["passed"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
