"""Verify and publish only the locally built jar, scripts and verification reports."""
import hashlib
import json
from pathlib import Path
import shutil
import struct
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT.parents[1] / "output/RemoteRagdoll"
jar = ROOT / "build/libs/RemoteRagdoll-1.0.0.jar"
test_suites = [ET.parse(path).getroot() for path in (ROOT / "build/test-results/test").glob("TEST-*.xml")]
assert test_suites, "JUnit results missing"
tests = sum(int(suite.attrib["tests"]) for suite in test_suites)
failures = sum(int(suite.attrib["failures"]) + int(suite.attrib["errors"]) for suite in test_suites)
assert failures == 0, "JUnit failures"
with zipfile.ZipFile(jar) as artifact:
    names = artifact.namelist()
    forbidden = ("com/github/retrooper/", "io/github/retrooper/", "org/EIQUI/GCBAPI/",
                 "com/ticxo/", "org/bukkit/", "net/minecraft/", "org/junit/", "org/mockito/")
    shaded = [name for name in names if name.startswith(forbidden)]
    assert not shaded, shaded
    plugin = artifact.read("plugin.yml").decode("utf-8")
    assert "depend: [GCBAPI, ModelEngine, packetevents]" in plugin
    assert "api-version: '26.3'" in plugin
    classes = [name for name in names if name.startswith("org/EIQUI/RemoteRagdoll/") and name.endswith(".class")]
    assert all(struct.unpack(">H", artifact.read(name)[6:8])[0] == 69 for name in classes)
    assert any(name.endswith("/visual/PacketVisual.class") for name in classes)
    natives = [name for name in names if name.endswith(("joltjni.dll", "libjoltjni.so"))]
    assert len(natives) == 2, natives

OUTPUT.mkdir(exist_ok=True, parents=True)
shutil.copy2(jar, OUTPUT / jar.name)
shutil.copy2(ROOT / "scripts/Physics.sk", OUTPUT / "Physics.sk")
shutil.copytree(ROOT / "build/test-results/test", OUTPUT / "validation/junit", dirs_exist_ok=True)
result = {
    "jar": jar.name,
    "sha256": hashlib.sha256(jar.read_bytes()).hexdigest(),
    "size": jar.stat().st_size,
    "packetEventsShaded": False,
    "compileOnlyDependenciesShaded": False,
    "classMajorVersion": 69,
    "pluginClassCount": len(classes),
    "nativeLibraries": natives,
    "unitTests": tests,
    "unitTestFailures": failures,
    "testSuites": {suite.attrib["name"]: int(suite.attrib["tests"]) for suite in test_suites},
    "pluginYaml": plugin,
    "visualClientVerification": "not performed",
}
(OUTPUT / "artifact-verification.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
print(json.dumps(result, indent=2))
