"""Read the legal14 Git snapshot; verify the two append-only render switches."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import os
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_REFERENCE = (GCBSERVER / 'legal14')
SOURCE_PATH = "파오캐/plugins/Skript/scripts/a/Physics.sk"
ADDITION = (
    "\n# 새로 생성하는 래그돌의 패킷 렌더 사용 여부.\n"
    "function Ragdoll_setPacketRender(b:boolean):\n"
    "\tset JoltRagdoll.PACKET_RENDER to {_b}\n"
    "\n"
    "function Ragdoll_getPacketRender() :: boolean:\n"
    "\treturn JoltRagdoll.PACKET_RENDER\n"
).encode("utf-8")


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path, default=DEFAULT_REFERENCE)
    parser.add_argument("--ref", default="dc7fe59a1434a8e5b0c4f3890b8a38f1104c29d9")
    parser.add_argument("--append", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT.parents[1] / "output/RemoteRagdoll/script-packet-audit.json")
    args = parser.parse_args()
    source = git(args.reference, "show", f"{args.ref}:{SOURCE_PATH}")
    baseline = source.replace(b"org.EIQUI.GCBAPI.physics.", b"org.EIQUI.RemoteRagdoll.")
    assert source.count(b"org.EIQUI.GCBAPI.physics.") == 5
    destination = ROOT / "scripts/Physics.sk"
    current = destination.read_bytes()
    if args.append and current == baseline:
        destination.write_bytes(current + ADDITION)
    current = destination.read_bytes()
    assert current == baseline + ADDITION, "Only five import substitutions and the exact appended functions are permitted"
    existing = re.findall(rb"^function [^\r\n]+", source, re.MULTILINE)
    functions = re.findall(rb"^function [^\r\n]+", current, re.MULTILINE)
    assert len(existing) == 58
    assert functions[:58] == existing and len(functions) == 60
    export = ROOT.parents[1] / "output/RemoteRagdoll/Physics.sk"
    export.parent.mkdir(parents=True, exist_ok=True)
    export.write_bytes(current)

    paths = git(args.reference, "ls-tree", "-r", "--name-only", "-z", args.ref, "--", "파오캐/plugins/Skript/scripts").decode("utf-8").split("\0")
    scripts = [path for path in paths if path.endswith(".sk")]
    terms = ("getDisplays", "getBodies", "setAngularVelocity", "Ragdoll_sweep", "GCB:Ragdoll")
    matches = {term: [] for term in terms}
    imports = []
    for path in scripts:
        content = git(args.reference, "show", f"{args.ref}:{path}").decode("utf-8-sig")
        for number, line in enumerate(content.splitlines(), 1):
            for term in terms:
                if term in line:
                    matches[term].append({"path": path, "line": number, "text": line.strip()})
            if re.search(r"org\.EIQUI\.GCBAPI\.physics\.(JoltNatives|PhysicsWorld|PhysicsBody|Ragdoll|Liquid)\b", line):
                imports.append({"path": path, "line": number, "text": line.strip()})
    report = {
        "referenceRepository": str(args.reference),
        "referenceCommit": git(args.reference, "rev-parse", args.ref).decode().strip(),
        "sourcePath": SOURCE_PATH,
        "sourceSha256": hashlib.sha256(source).hexdigest(),
        "exportSha256": hashlib.sha256(current).hexdigest(),
        "importSubstitutions": 5,
        "originalBytesOtherwisePreserved": True,
        "originalFunctionsPreserved": len(existing),
        "addedFunctions": [line.decode() for line in functions[58:]],
        "scannedScripts": len(scripts),
        "directImports": imports,
        "matches": matches,
        "exportMatchesRepository": export.read_bytes() == current,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
