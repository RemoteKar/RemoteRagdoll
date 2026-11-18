"""Reproduce local 26.3 / PacketEvents bytecode evidence without modifying inputs."""
from pathlib import Path
import subprocess
import zipfile
import hashlib
import os
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))

ROOT = Path(__file__).resolve().parents[1]
JAVAP = (JDK / 'bin' / 'javap.exe')
NMS = ROOT / '.gradle/caches/paperweight/taskCache/mappedServerJar.jar'
PE = (GCBSERVER / '20260412FOC/personal/파오캐/plugins/packetevents-spigot-2.14.0.jar')
CLIENT = (GCBSERVER / 'ScreenRoll/build/minecraft-26.3-client.jar')
OUT = ROOT.parents[1] / 'output/RemoteRagdoll/validation/packet-bytecode'
OUT.mkdir(parents=True, exist_ok=True)

targets = {
    NMS: ['net.minecraft.world.entity.Entity', 'net.minecraft.world.entity.Display',
          'net.minecraft.world.entity.Display$ItemDisplay', 'org.bukkit.craftbukkit.util.CraftMagicNumbers',
          'net.minecraft.network.syncher.SynchedEntityData', 'net.minecraft.network.syncher.EntityDataSerializers',
          'net.minecraft.server.level.ServerEntity', 'net.minecraft.server.level.ServerLevel',
          'net.minecraft.world.entity.InterpolationHandler', 'net.minecraft.world.item.ItemDisplayContext'],
    PE: ['io.github.retrooper.packetevents.util.SpigotConversionUtil',
         'io.github.retrooper.packetevents.util.SpigotReflectionUtil',
         'com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes',
         'com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile',
         'com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile$Property',
         'com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile$SkinPatch',
         'com.github.retrooper.packetevents.protocol.component.builtin.item.ItemCustomModelData',
         'com.github.retrooper.packetevents.protocol.component.ComponentTypes',
         'com.github.retrooper.packetevents.protocol.item.ItemStack',
         'com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync',
         'com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport'],
    CLIENT: ['net.minecraft.client.multiplayer.ClientPacketListener',
             'net.minecraft.world.entity.Display', 'net.minecraft.world.entity.Entity',
             'net.minecraft.world.entity.InterpolationHandler'],
}
for jar, classes in targets.items():
    prefix = 'client' if jar == CLIENT else 'pe' if jar == PE else 'nms'
    print(prefix, jar, hashlib.sha256(jar.read_bytes()).hexdigest())
    for cls in classes:
        result = subprocess.run([str(JAVAP), '-p', '-c', '-cp', str(jar), cls],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        destination = OUT / (prefix + '-' + cls.rsplit('.', 1)[-1] + '.txt')
        destination.write_bytes(result.stdout)
        print(destination.name, result.returncode)
for jar in [PE, ROOT / 'libs/GCBAPI-1.2.0-26.3.jar', ROOT / 'libs/ModelEngine-R4.1.1.jar']:
    with zipfile.ZipFile(jar) as z:
        print('\nPLUGIN', jar.name, '\n', z.read('plugin.yml').decode('utf-8'))
