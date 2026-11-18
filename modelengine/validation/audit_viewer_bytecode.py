"""Disassemble the local 26.3 client/server viewer lifecycle with pinned Java 25."""
from pathlib import Path
import hashlib
import json
import subprocess
import zipfile
import os
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))

ROOT = Path(__file__).resolve().parents[1]
JAVAP = (JDK / 'bin' / 'javap.exe')
CLIENT = (GCBSERVER / 'ScreenRoll/build/minecraft-26.3-client.jar')
SERVER = ROOT / '.gradle/caches/paperweight/taskCache/mappedServerJar.jar'
OUT = ROOT / 'build/viewer-bytecode'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(CLIENT) as archive:
        version = json.loads(archive.read('version.json'))
    if version['id'] != '26.3':
        raise RuntimeError(f"Expected client 26.3, found {version['id']}")
    evidence = {'client': str(CLIENT), 'sha256': hashlib.sha256(CLIENT.read_bytes()).hexdigest(),
                'version': version['id'], 'server': str(SERVER),
                'server_sha256': hashlib.sha256(SERVER.read_bytes()).hexdigest(),
                'javap': str(JAVAP), 'classes': []}
    targets = {
        CLIENT: ['net.minecraft.client.multiplayer.ClientPacketListener',
                 'net.minecraft.client.multiplayer.ClientLevel',
                 'net.minecraft.client.multiplayer.LevelLoadTracker',
                 'net.minecraft.client.multiplayer.MultiPlayerGameMode'],
        SERVER: ['net.minecraft.server.players.PlayerList',
                 'net.minecraft.server.level.ServerPlayer',
                 'org.bukkit.craftbukkit.entity.CraftPlayer'],
    }
    for jar, names in targets.items():
        for name in names:
            result = subprocess.run([str(JAVAP), '-p', '-c', '-cp', str(jar), name],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            destination = OUT / (name.rsplit('.', 1)[-1] + '.txt')
            destination.write_bytes(result.stdout)
            evidence['classes'].append({'class': name, 'output': str(destination.relative_to(ROOT)),
                                        'sha256': hashlib.sha256(result.stdout).hexdigest()})
    (OUT / 'inputs.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(evidence, indent=2))


if __name__ == '__main__':
    main()
