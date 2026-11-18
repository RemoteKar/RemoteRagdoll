"""Compare unchanged physics and render mathematics with the requested baseline commit."""
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE = "9c67679"
PACKAGE = "src/main/java/org/EIQUI/RemoteRagdoll/"


def original(name):
    return subprocess.check_output(["git", "show", f"{BASE}:{PACKAGE}{name}.java"], cwd=ROOT).decode("utf-8")


def current(name):
    return (ROOT / PACKAGE / f"{name}.java").read_text(encoding="utf-8")


def method(source, declaration):
    start = source.index(declaration)
    opening = source.index("{", start)
    depth = 1
    cursor = opening + 1
    while depth:
        depth += (source[cursor] == "{") - (source[cursor] == "}")
        cursor += 1
    return source[start:cursor]


def compact(source):
    return re.sub(r"\s+", "", source)


results = {}
for name in ("PhysicsBody", "PhysicsWorld", "Ragdoll", "Liquid", "JoltNatives"):
    declarations = lambda source: {compact(match) for match in re.findall(r"(?m)^    public [^{;]+[;{]", source)}
    assert declarations(original(name)) <= declarations(current(name)), f"public API removed/changed: {name}"
    results[name + ":public_api"] = "all baseline declarations/defaults preserved"
for name in ("Liquid", "TerrainCache", "EntityCache", "JoltNatives"):
    assert original(name) == current(name), name
    results[name] = "unchanged"

for name, declarations in {
    "PhysicsBody": ["private void release()", "public void detach()", "public void freeze()",
                    "public void setVelocity(", "public void setAngularVelocity(",
                    "public void addImpulse(", "public void addTorque(", "public Vector getVelocity(",
                    "public Location toWorld("],
    "PhysicsWorld": ["private void tick()", "public Body createBoxBody(Location at, Vec3Arg halfExtent, QuatArg rotation, float mass, int layer,",
                     "public void remove(int bodyId)", "void trackRagdoll("],
    "Ragdoll": ["private void link(", "private void weld(", "public boolean dismember(",
                "public int dismemberAll(", "boolean tickLifetime()", "public void destroy()",
                "private static double limbYOffset(", "public static int limbTest("],
}.items():
    for declaration in declarations:
        assert method(original(name), declaration) == method(current(name), declaration), declaration
        results[name + ":" + declaration] = "unchanged"

old = method(original("PhysicsBody"), "void onPhysicsTick(")
old = old[old.index("Quaternionf q ="):]
new = method(current("PhysicsBody"), "private void renderVisuals(")
new = new[new.index("Quaternionf q ="):].replace("visuals", "displays")
new = new.replace("            // Sleeping packet bodies produce no update packets. Keep the legacy entity path intact.\n", "")
new = new.replace("            if (!active && displays.get(i).isPacket()) continue;\n", "")
new = new.replace("            if (packetsOnly && !displays.get(i).isPacket()) continue;\n", "")
new = new.replace("displays.get(i).setTransform(off, left, scales.get(i), rightRots.get(i));",
                  "displays.get(i).setTransformation(new Transformation(off, left, scales.get(i), rightRots.get(i)));")
new = new.replace("displays.get(i).moveTo(to);", "displays.get(i).teleport(to);")
assert compact(old) == compact(new), "physics render mathematics changed"
results["render_math"] = "identical after adapter-call and sleep-guard normalization"
destination = ROOT.parents[1] / "output/RemoteRagdoll/render-contract-audit.json"
destination.write_text(json.dumps({"baseline": BASE, "checks": results}, indent=2) + "\n", encoding="utf-8")
print(f"PASS: {len(results)} baseline contract checks")
