"""Compile the isolated test plugin using local 26.3 dependencies, no server edits."""
from pathlib import Path
import os
import subprocess
import zipfile
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))

ROOT = Path(__file__).resolve().parents[1]
JAVA = (JDK / 'bin')
SERVER = ROOT / 'run-test'
BUILD = ROOT / 'build/validation/packet-probe'
BUILD.mkdir(parents=True, exist_ok=True)
classpath = [ROOT / '.gradle/caches/paperweight/taskCache/mappedServerJar.jar',
             ROOT / 'libs/packetevents-spigot-2.14.0.jar']
classpath.extend(SERVER.joinpath('libraries').rglob('*.jar'))
args = ['-encoding', 'UTF-8', '-cp', os.pathsep.join(map(str, classpath)),
        '-d', str(BUILD), str(ROOT / 'validation/PacketComponentProbe.java')]
argfile = BUILD / 'javac.args'
argfile.write_text('\n'.join('"' + arg.replace('\\', '/') + '"' for arg in args), encoding='utf-8')
subprocess.run([str(JAVA / 'javac.exe'), '@' + str(argfile)], check=True)
destination = ROOT / 'run-test/plugins/PacketComponentProbe.jar'
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
    archive.write(BUILD / 'PacketComponentProbe.class', 'PacketComponentProbe.class')
    archive.writestr('plugin.yml', 'name: PacketComponentProbe\nversion: 1.0\nmain: PacketComponentProbe\napi-version: \'26.3\'\ndepend: [packetevents, RemoteRagdoll]\n')
    archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n\n')
print(destination)
