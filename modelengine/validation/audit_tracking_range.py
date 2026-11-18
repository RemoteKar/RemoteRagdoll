"""Record and verify the pinned 26.3 Display tracking bytecode (read-only inputs)."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import zipfile
import os
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))

ROOT = Path(__file__).resolve().parents[1]
JAVAP = (JDK / 'bin' / 'javap.exe')
SERVER = ROOT / '.gradle/caches/paperweight/taskCache/mappedServerJar.jar'
API_ROOT = Path.home() / '.gradle/caches/modules-2/files-2.1/io.papermc.paper/paper-api/26.3.build.5-alpha'
OUT = ROOT / 'build/tracking-bytecode'


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def method(text, signature):
    start = text.index('  ' + signature)
    end = re.search(r'\n  (?:public|protected|private|static) ', text[start + 3:])
    return text[start:] if end is None else text[start:start + 3 + end.start()]


def main():
    api_jars = list(API_ROOT.glob('*/paper-api-26.3.build.5-alpha.jar'))
    if len(api_jars) != 1:
        raise RuntimeError(f'Expected one pinned Paper API jar, found {api_jars}')
    api = api_jars[0]
    OUT.mkdir(parents=True, exist_ok=True)
    evidence = {
        'javap': str(JAVAP),
        'inputs': [{'path': str(path), 'sha256': sha256(path)} for path in (SERVER, api)],
        'classes': [],
    }
    targets = {
        SERVER: [
            'org.spigotmc.TrackingRange',
            'org.spigotmc.SpigotWorldConfig',
            'net.minecraft.server.level.ChunkMap',
            'net.minecraft.server.level.ChunkMap$TrackedEntity',
            'net.minecraft.server.MinecraftServer',
            'net.minecraft.server.dedicated.DedicatedServer',
            'net.minecraft.world.level.Level',
            'net.minecraft.world.entity.Display',
            'io.papermc.paper.FeatureHooks',
            'io.papermc.paper.entity.activation.ActivationType',
            'io.papermc.paper.configuration.WorldConfiguration$Entities$TrackingRangeY',
            'org.bukkit.craftbukkit.CraftWorld',
            'org.bukkit.craftbukkit.entity.CraftPlayer',
        ],
        api: ['org.bukkit.World', 'org.bukkit.World$Spigot', 'org.bukkit.entity.Display',
              'org.bukkit.entity.Entity', 'org.bukkit.entity.Player', 'org.bukkit.Server$Spigot',
              'io.papermc.paper.configuration.ServerConfiguration'],
    }
    texts = {}
    for jar, classes in targets.items():
        for name in classes:
            result = subprocess.run([str(JAVAP), '-p', '-c', '-cp', str(jar), name],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            destination = OUT / (name + '.txt')
            destination.write_bytes(result.stdout)
            texts[name] = result.stdout.decode('utf-8')
            evidence['classes'].append({'class': name, 'output': str(destination.relative_to(ROOT)),
                                        'sha256': hashlib.sha256(result.stdout).hexdigest()})

    with zipfile.ZipFile(SERVER) as archive:
        activation_range_classes = [name for name in archive.namelist()
                                    if name.endswith('ActivationRange.class')]
    evidence['activation_range_classes'] = activation_range_classes
    checks = {}
    tracking = texts['org.spigotmc.TrackingRange']
    checks['display_uses_loaded_spigot_field'] = bool(re.search(
        r'instanceof\s+[^\n]+// class net/minecraft/world/entity/Display\s*'
        r'\d+: ifeq\s+\d+\s*\d+: aload_2\s*\d+: getfield[^\n]+'
        r'org/spigotmc/SpigotWorldConfig.displayTrackingRange:I\s*\d+: ireturn', tracking))
    checks['spigot_display_default_128'] = bool(re.search(
        r'sipush\s+128\s*\d+: putfield[^\n]+Field displayTrackingRange:I',
        texts['org.spigotmc.SpigotWorldConfig']))
    startup_view = method(texts['org.spigotmc.SpigotWorldConfig'], 'private void viewDistance(')
    checks['spigot_startup_world_view_clamp_3_to_32'] = bool(re.search(
        r'Field viewDistance:I\s*\d+: bipush\s+32\s*'
        r'\d+: invokestatic[^\n]+Math.min:\(II\)I\s*\d+: iconst_3\s*'
        r'\d+: invokestatic[^\n]+Math.max:\(II\)I\s*'
        r'\d+: putfield[^\n]+Field viewDistance:I', startup_view))
    chunks = texts['net.minecraft.server.level.ChunkMap']
    add = method(chunks, 'protected void addEntity(')
    checks['zero_tracking_skips_tracker'] = bool(re.search(
        r'TrackingRange.getEntityTrackingRange[^\n]+\s*\d+: istore_3\s*'
        r'\d+: iload_3\s*\d+: ifeq', add))
    view = method(chunks, 'private int getPlayerViewDistance(')
    checks['player_view_clamp_2_to_world'] = all(fragment in view for fragment in (
        'requestedViewDistance:()I', 'iconst_2', 'Field serverViewDistance:I', 'Mth.clamp:(III)I'))
    update = method(texts['net.minecraft.server.level.ChunkMap$TrackedEntity'],
                    'public void updatePlayer(')
    before_vertical = update[:update.index('Field io/papermc/paper/configuration/WorldConfiguration.entities')]
    checks['horizontal_inclusive_radius'] = all(fragment in before_vertical for fragment in (
        'ServerPlayer.getX:()D', 'ServerPlayer.getZ:()D', 'dadd', 'dcmpg', 'ifgt')) \
        and 'getY:()D' not in before_vertical
    checks['view_distance_times_16_cap'] = bool(re.search(
        r'Method getEffectiveRange:\(\)I\s*\d+: iload\s+6\s*'
        r'\d+: bipush\s+16\s*\d+: imul\s*\d+: invokestatic[^\n]+Math.min:\(II\)I', update))
    checks['optional_separate_vertical_cap'] = all(fragment in update for fragment in (
        'TrackingRangeY.enabled:Z', 'TrackingRangeY.get:', 'double -1.0d', 'ServerPlayer.getY:()D'))
    vertical = texts['io.papermc.paper.configuration.WorldConfiguration$Entities$TrackingRangeY']
    checks['vertical_default_disabled'] = bool(re.search(
        r'iconst_0\s*\d+: putfield[^\n]+Field enabled:Z', vertical))
    checks['vertical_display_default_minus_one'] = all(fragment in vertical for fragment in (
        'IntOr$Default.USE_DEFAULT:', 'Field display:', 'IntOr$Default.or:(I)I'))
    scaled = method(texts['net.minecraft.server.dedicated.DedicatedServer'],
                    'public int getScaledTrackingDistance(')
    checks['dedicated_percentage_integer_scaling'] = all(fragment in scaled for fragment in (
        'entityBroadcastRangePercentage:()I', 'imul', 'bipush        100', 'idiv'))
    world_view = method(texts['io.papermc.paper.FeatureHooks'],
                        'public static int getViewDistance(net.minecraft.server.level.ServerLevel)')
    checks['world_api_is_server_view_distance'] = 'ChunkMap.serverViewDistance:I' in world_view
    client_view = method(texts['org.bukkit.craftbukkit.entity.CraftPlayer'],
                         'public int getClientViewDistance(')
    checks['player_api_is_requested_view_distance'] = 'ServerPlayer.requestedViewDistance:()I' in client_view
    display = method(texts['net.minecraft.world.entity.Display'], 'public boolean shouldRenderAtSqrDistance(')
    checks['client_culling_view_range_times_64_times_scale'] = all(fragment in display for fragment in (
        'getViewRange:()F', 'double 64.0d', 'getViewScale:()D', 'Mth.square:(D)D', 'ifge'))
    activation = method(texts['io.papermc.paper.entity.activation.ActivationType'],
                        'public static io.papermc.paper.entity.activation.ActivationType activationTypeFor(')
    checks['display_activation_falls_through_to_misc'] = 'Display' not in activation \
        and 'Field MISC:' in activation and not activation_range_classes
    api_text = '\n'.join(texts[name] for name in targets[api])
    checks['no_typed_display_tracking_range_api'] = 'getTrackingRange(' not in api_text \
        and 'getEntityTrackingRange(' not in api_text and 'getDisplayTrackingRange(' not in api_text
    evidence['checks'] = checks
    (OUT / 'inputs.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'checks': checks, 'evidence': str(OUT / 'inputs.json')}, indent=2))
    if not all(checks.values()):
        raise RuntimeError('Tracking bytecode audit failed; inspect the pinned disassembly before updating rules')


if __name__ == '__main__':
    main()
