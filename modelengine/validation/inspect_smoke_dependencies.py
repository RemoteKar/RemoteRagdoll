"""Inspect copied/read-only plugin descriptors before creating a smoke server."""

from pathlib import Path
import zipfile
import os
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))


root = Path(__file__).resolve().parents[1]
reference = (GCBSERVER / '20260412FOC/personal/파오캐')
paths = [root / "libs/GCBAPI-1.2.0-26.3.jar", root / "libs/ModelEngine-R4.1.1.jar", reference / "plugins/packetevents-spigot-2.14.0.jar", reference / "server.jar"]
for path in paths:
    with zipfile.ZipFile(path) as archive:
        print(str(path).encode("ascii", "backslashreplace").decode())
        entries = ["plugin.yml"] if path.name != "server.jar" else ["META-INF/MANIFEST.MF", "version.json", "META-INF/versions.list"]
        for name in entries:
            if name in archive.namelist():
                print(archive.read(name).decode("utf-8").encode("ascii", "backslashreplace").decode())
