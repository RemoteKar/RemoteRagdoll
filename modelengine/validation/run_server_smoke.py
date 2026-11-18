"""Prepare and run an isolated 26.3 server; always stop the owned JVM."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import shutil
import socket
import subprocess
import threading
import time
# Machine-specific locations: GCBSERVER_ROOT (default: the GCBServer folder beside the folder holding this checkout)
# and JAVA_HOME (a JDK 25).
GCBSERVER = Path(os.environ.get('GCBSERVER_ROOT', Path(__file__).resolve().parents[4] / 'GCBServer'))
JDK = Path(os.environ.get('JAVA_HOME', GCBSERVER / 'Java' / 'Java_25'))


ROOT = Path(__file__).resolve().parents[1]
REFERENCE = (GCBSERVER / '20260412FOC/personal/파오캐')
JAVA = (JDK / 'bin' / 'java.exe')


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_port(port):
    if port == 25585:
        raise ValueError("Port 25585 is reserved for client AuthMe and must never be used")
    for kind in (socket.SOCK_STREAM, socket.SOCK_DGRAM):
        with socket.socket(socket.AF_INET, kind) as check:
            if os.name == "nt":
                check.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            check.bind(("0.0.0.0", port))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=25643)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--view-distance", type=int, default=2)
    parser.add_argument("--display-tracking-range", type=int,
                        help="Write an isolated spigot.yml tracking-range fixture before startup")
    parser.add_argument("--jar", type=Path, default=ROOT / "build/libs/RemoteRagdoll-1.0.0.jar")
    parser.add_argument("--probe", type=Path)
    parser.add_argument("--success-marker", action="append", default=[])
    parser.add_argument("--failure-marker", action="append", default=["PACKET_COMPONENT_PROBE_FAILED"])
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--name", default="server-smoke")
    args = parser.parse_args()
    if not 2 <= args.view_distance <= 32:
        parser.error("--view-distance must be between 2 and 32 chunks")
    if args.display_tracking_range is not None and args.display_tracking_range <= 0:
        parser.error("--display-tracking-range must be positive")
    run = (ROOT / "run-test").resolve()
    assert run.parent == ROOT.resolve()
    check_port(args.port)
    run.mkdir(exist_ok=True)
    plugins = run / "plugins"
    plugins.mkdir(exist_ok=True)
    (run / "eula.txt").write_text("eula=true\n", encoding="ascii")
    (run / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\n"
        "enable-rcon=false\nenable-query=false\nenable-jmx-monitoring=false\n"
        "management-server-enabled=false\nonline-mode=false\nwhite-list=true\n"
        "enforce-whitelist=true\nspawn-protection=0\nmax-players=1\n"
        f"view-distance={args.view_distance}\nsimulation-distance=2\nlevel-type=minecraft:normal\n"
        "generate-structures=false\nspawn-monsters=false\nspawn-animals=false\n"
        "max-tick-time=60000\nmotd=RemoteRagdoll isolated smoke\n",
        encoding="ascii",
    )
    if args.display_tracking_range is not None:
        (run / "spigot.yml").write_text(
            "# Isolated tracking-range smoke fixture; never copied to the source server.\n"
            "world-settings:\n  default:\n    entity-tracking-range:\n"
            f"      display: {args.display_tracking_range}\n",
            encoding="ascii",
        )
    copies = {
        REFERENCE / "server.jar": run / "server.jar",
        ROOT / "libs/GCBAPI-1.2.0-26.3.jar": plugins / "GCBAPI-1.2.0.jar",
        ROOT / "libs/ModelEngine-R4.1.1.jar": plugins / "ModelEngine-R4.1.1.jar",
        REFERENCE / "plugins/packetevents-spigot-2.14.0.jar": plugins / "packetevents-spigot-2.14.0.jar",
        REFERENCE / "plugins/z-Skript-2.13.2.jar": plugins / "z-Skript-2.13.2.jar",
        REFERENCE / "plugins/BetterHud-bukkit-2.1.0-26.3.jar": plugins / "BetterHud-bukkit-2.1.0-26.3.jar",
        REFERENCE / "plugins/PlaceholderAPI-2.11.6-patched.jar": plugins / "PlaceholderAPI-2.11.6-patched.jar",
    }
    if args.jar.exists():
        copies[args.jar.resolve()] = plugins / "RemoteRagdoll-1.0.0.jar"
    elif not args.prepare_only:
        raise FileNotFoundError(args.jar)
    if args.probe:
        copies[args.probe.resolve()] = plugins / args.probe.name
    for source, destination in copies.items():
        if source.resolve() != destination.resolve():
            shutil.copy2(source, destination)
    # Reuse only vanilla library/cache files; plugin configurations and worlds are fresh.
    for child in ("libraries", "cache", "versions"):
        source = REFERENCE / child
        if source.is_dir():
            shutil.copytree(source, run / child, dirs_exist_ok=True)
    manifest = {str(destination.relative_to(run)): sha256(destination) for destination in copies.values()}
    output = ROOT.parents[1] / "output/RemoteRagdoll/validation"
    output.mkdir(parents=True, exist_ok=True)
    result_path = output / f"{args.name}.json"
    report = {"port": args.port, "address": "127.0.0.1", "queryEnabled": False,
              "rconEnabled": False, "portCheckedFreeBeforeStart": True,
              "viewDistanceChunks": args.view_distance,
              "displayTrackingRangeFixture": args.display_tracking_range,
              "java": str(JAVA), "pluginsAndServerSha256": manifest}
    if args.prepare_only:
        report["preparedOnly"] = True
        result_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print("Prepared isolated server; no JVM started", flush=True)
        return

    check_port(args.port)
    lines = []
    chunks = queue.Queue()
    log_path = output / f"{args.name}.log"
    command = [str(JAVA), "--enable-native-access=ALL-UNNAMED", "-Xms512M", "-Xmx1536M",
               "-Dfile.encoding=UTF-8", "-Dpaper.disable-spark=true",
               "-jar", "server.jar", "nogui"]
    started = time.monotonic()
    process = subprocess.Popen(command, cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    report["pid"] = process.pid
    report["command"] = command
    print(f"Started owned test JVM pid={process.pid}, localhost:{args.port}", flush=True)

    def read_output():
        for line in process.stdout:
            chunks.put(line)

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    ready_at = None
    next_progress = started
    forced = False
    try:
        with log_path.open("w", encoding="utf-8") as log:
            while time.monotonic() - started < args.timeout:
                try:
                    line = chunks.get(timeout=0.2)
                except queue.Empty:
                    line = None
                if line is not None:
                    lines.append(line)
                    log.write(line)
                    log.flush()
                    if "Done (" in line:
                        ready_at = time.monotonic()
                    if any(token in line for token in ("RemoteRagdoll", "PROBE", "ERROR", "Exception", "Done (")):
                        print(line.rstrip().encode("ascii", "backslashreplace").decode(), flush=True)
                joined = "".join(lines)
                if ready_at and any(marker in joined for marker in args.failure_marker):
                    break
                if ready_at and time.monotonic() - ready_at >= 8 and all(marker in joined for marker in args.success_marker):
                    break
                if process.poll() is not None and chunks.empty():
                    break
                if time.monotonic() >= next_progress:
                    print(f"Smoke running: {int(time.monotonic() - started)}s, serverReady={ready_at is not None}", flush=True)
                    next_progress = time.monotonic() + 15
    finally:
        if process.poll() is None:
            try:
                process.stdin.write("stop\n")
                process.stdin.flush()
                process.wait(timeout=45)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                forced = True
                process.kill()
                process.wait(timeout=15)
        reader.join(timeout=5)
        with log_path.open("a", encoding="utf-8") as log:
            while not chunks.empty():
                line = chunks.get_nowait()
                lines.append(line)
                log.write(line)
        joined = "".join(lines)
        report.update({"exitCode": process.returncode, "forcedTermination": forced,
                       "ownedJvmStopped": process.poll() is not None, "durationSeconds": round(time.monotonic() - started, 2),
                       "serverReady": ready_at is not None,
                       "gcbApiEnableLogged": "[GCBAPI] Enabling GCBAPI" in joined,
                       "gcbApiDisableLogged": "[GCBAPI] Disabling GCBAPI" in joined,
                       "remoteRagdollEnableLogged": "[RemoteRagdoll] Enabling RemoteRagdoll" in joined,
                       "remoteRagdollDisableLogged": "[RemoteRagdoll] Disabling RemoteRagdoll" in joined,
                       "successMarkers": {marker: marker in joined for marker in args.success_marker},
                       "errorLines": [line.strip() for line in lines if "ERROR" in line or "Exception" in line],
                       "log": str(log_path)})
        try:
            check_port(args.port)
            report["portFreeAfterShutdown"] = True
        except OSError:
            report["portFreeAfterShutdown"] = False
        report["fullSmokePassed"] = (report["serverReady"] and report["gcbApiEnableLogged"]
                and report["gcbApiDisableLogged"] and report["remoteRagdollEnableLogged"]
                and report["remoteRagdollDisableLogged"] and report["exitCode"] == 0 and not forced
                and not report["errorLines"] and all(report["successMarkers"].values()))
        result_path.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, ensure_ascii=True, indent=2), flush=True)
    raise SystemExit(0 if report["fullSmokePassed"] else 1)


if __name__ == "__main__":
    main()
