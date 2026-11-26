#!/usr/bin/env python3
"""Launch an isolated Linux Fabric client in a private Xvfb display; no existing world or launcher profile is used."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import fcntl
import signal
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
from urllib.request import urlopen

HERE = Path(__file__).resolve().parent


def sha1_file(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha1").hexdigest()


def store_asset(path, expected_sha1, url, offline):
    if path.is_file() and sha1_file(path) == expected_sha1:
        return
    if offline:
        raise ValueError(f"Missing verified asset {path}; rerun with --online to obtain it")
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as out:
        tmp = Path(out.name)
        try:
            with urlopen(url, timeout=45) as response:
                shutil.copyfileobj(response, out)
            out.flush()
            if sha1_file(tmp) != expected_sha1:
                raise ValueError(f"SHA1 mismatch for {url}")
            os.replace(tmp, path)
        finally:
            tmp.unlink(missing_ok=True)


def visual_asset(name):
    return (name.startswith("icons/icon") and name.endswith(".png")
            or name.startswith(("minecraft/font/", "minecraft/textures/"))
            or name in {"pack.mcmeta", "minecraft/lang/zh_cn.json"})


def assets(gradle_home, version, cache, mode, online):
    metadata_file = gradle_home / "caches" / "fabric-loom" / version / "mojang_minecraft_info.json"
    index = json.loads(metadata_file.read_text())["assetIndex"]
    if urlsplit(index["url"]).hostname not in {"piston-meta.mojang.com", "launchermeta.mojang.com"} or not index["url"].startswith("https://"):
        raise ValueError("Unsupported Minecraft resource-index host")
    base = cache / "assets"
    index_id = index["id"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", index_id):
        raise ValueError("Invalid asset index ID")
    official = base / "indexes" / (index_id + "-official.json")
    store_asset(official, index["sha1"], index["url"], not online)
    objects = json.loads(official.read_text())["objects"]
    if mode == "visual":
        objects = {key: value for key, value in objects.items() if visual_asset(key)}

    def ensure(item):
        obj = item["hash"]
        if not re.fullmatch(r"[a-f0-9]{40}", obj):
            raise ValueError("Invalid resource hash")
        store_asset(base / "objects" / obj[:2] / obj, obj, "https://resources.download.minecraft.net/" + obj[:2] + "/" + obj, not online)
    with ThreadPoolExecutor(max_workers=6) as pool:
        list(pool.map(ensure, objects.values()))
    if mode == "visual":
        index_id += "-visual"
        index_file = base / "indexes" / (index_id + ".json")
        encoded = json.dumps({"objects":objects}).encode()
        with tempfile.NamedTemporaryFile(dir=index_file.parent, delete=False) as temp:
            temp.write(encoded)
        os.replace(temp.name, index_file)
    else:
        index_id += "-official"
    return base, index_id


def hash_files(files):
    digest = hashlib.sha256()
    for name, path in sorted(files):
        digest.update(os.fsencode(name) + b'\0')
        if path.is_file():
            with path.open('rb') as stream:
                digest.update(hashlib.file_digest(stream, 'sha256').digest())
        else:
            digest.update(b'missing')
    return digest.hexdigest()


def build_identity(project, outputs):
    tracked = subprocess.check_output([
        'git','-C',str(project),'ls-files','--cached','--others','--exclude-standard','-z','--',
        'src','versions','settings.json','settings.gradle','build.gradle','common.gradle','gradle.properties','gradle','gradlew'])
    sources = [(os.fsdecode(n), project / os.fsdecode(n)) for n in tracked.split(b'\0') if n]
    compiled = []
    for root_name in outputs:
        root = Path(root_name)
        for path in root.rglob('*'):
            if path.is_file():
                compiled.append((str(path.relative_to(project)), path))
    if not compiled:
        raise ValueError('The requested checkout has no compiled P2S classes or resources.')
    return {'commit':subprocess.check_output(['git','-C',str(project),'rev-parse','HEAD'],text=True).strip(),
            'inputsSha256':hash_files(sources), 'outputsSha256':hash_files(compiled)}


def gradle_manifest(args, java_home, run, gradle_home):
    if args.classpath_manifest:
        manifest = json.loads(args.classpath_manifest.read_text())
        if manifest['version'] != args.version or Path(manifest['project']).resolve() != args.project:
            raise ValueError('Classpath manifest belongs to a different checkout or Minecraft version.')
        if manifest.get('identity') != build_identity(args.project, manifest['projectOutputs']):
            raise ValueError('Checkout or compiled outputs have changed (or manifest predates fingerprinting). Omit --classpath-manifest to rebuild.')
        return manifest
    output = run / "classpath.json"
    cmd = [str(args.project / "gradlew"), "--no-daemon", "--max-workers=2", "--console=plain",
           "-I", str(HERE / "classpath.gradle"), "-Dp2s.test.version=" + args.version,
           "-Dp2s.test.manifest=" + str(output), ":" + args.version + ":p2sAcceptanceClasspath"]
    if not args.online:
        cmd.append("--offline")
    env = dict(os.environ, JAVA_HOME=str(java_home), GRADLE_USER_HOME=str(gradle_home))
    subprocess.run(cmd, cwd=args.project, env=env, check=True)
    manifest = json.loads(output.read_text())
    manifest['identity'] = build_identity(args.project, manifest['projectOutputs'])
    output.write_text(json.dumps(manifest, indent=2)+'\n')
    return manifest


def development_args(manifest, args):
    if manifest["version"] != args.version or Path(manifest["project"]).resolve() != args.project:
        raise ValueError("Classpath manifest does not belong to the requested checkout and Minecraft version")
    cp = [Path(p) for p in manifest["runtimeClasspath"]]
    outputs = [Path(p) for p in manifest["projectOutputs"]]
    if not cp or any(not p.exists() for p in cp + outputs):
        raise ValueError("The target checkout's compiled classes or runtime libraries are missing")
    # Loom's split environment jars for a Fabric API module represent one mod origin.
    pairs = {}
    for p in cp:
        if "remapped_mods" in p.parts:
            module, _, side = p.parent.parent.name.rpartition("-")
            if side in {"client", "common"}:
                pairs.setdefault(module, []).append(p)
    groups = [outputs] + [pair for pair in pairs.values() if len(pair) > 1]
    def game_jar(prefix):
        matches = [p for p in cp if p.name.startswith(prefix) and p.suffix == ".jar"]
        if len(matches) != 1:
            raise ValueError(f"Expected one {prefix} jar, found {matches}")
        return str(matches[0])
    return ["-Dfabric.development=true", "-Dfabric.gameJarPath=" + game_jar("minecraft-common-"),
            "-Dfabric.gameJarPath.client=" + game_jar("minecraft-clientOnly-"),
            "-Dfabric.classPathGroups=" + (os.pathsep * 2).join(os.pathsep.join(map(str, group)) for group in groups),
            "-cp", os.pathsep.join(map(str, cp)), "net.fabricmc.loader.impl.launch.knot.KnotClient"]


def game_options(args):
    return ("version:3953\nonboardAccessibility:false\nfullscreen:false\nsoundCategory_master:0.0\n"
            "renderDistance:3\nsimulationDistance:3\nmaxFps:30\nenableVsync:false\ntutorialStep:none\n"
            f"guiScale:{args.gui_scale}\nlang:{args.lang}\n")


def private_game(run, args):
    game = run / "game"
    marker = game / ".p2s-acceptance-world-fixture"
    if game.exists() and not marker.is_file() and any(game.iterdir()):
        raise ValueError(f"Refusing to use existing game directory without fixture marker: {game}")
    game.mkdir(parents=True, exist_ok=True)
    marker.touch(mode=0o600)
    (run / "profile").mkdir(exist_ok=True)
    if args.config:
        (game / "config").mkdir(exist_ok=True)
        src = json.loads(args.config.read_text())
        (game / "config" / "p2s_client.json").write_text(json.dumps(src, ensure_ascii=False))
    if args.world_template:
        source = args.world_template.resolve()
        if not (source / ".p2s-world-template").is_file() or not (source / "level.dat").is_file():
            raise ValueError("Expected an explicitly exported acceptance world template")
        if not args.quick_world:
            args.quick_world = source.name
        dest = game / "saves" / args.quick_world
        dest.parent.mkdir(exist_ok=True)
        if dest.exists():
            raise ValueError(f"Template destination already exists: {dest}")
        shutil.copytree(source, dest, ignore=shutil.ignore_patterns("session.lock", ".p2s-world-template"))
    if args.quick_world and not (game / "saves" / args.quick_world / "level.dat").is_file():
        raise ValueError(f"Quick-play world does not exist: {args.quick_world}")
    (game / "options.txt").write_text(game_options(args))
    return game


def java_home(value):
    java = (Path(value) / "bin" / "java") if value else Path(shutil.which("java") or "")
    java = java.resolve()
    if not java.is_file():
        raise ValueError("Java 21 JDK is required; set --java-home or JAVA_HOME")
    info = subprocess.run([java, "-version"], capture_output=True, text=True, check=True)
    if 'version "21.' not in info.stderr:
        raise ValueError("Java 21 JDK is required; set --java-home or JAVA_HOME")
    return java.parent.parent


def fabric_versions(project, version):
    path = project / "versions" / version / "gradle.properties"
    values = dict(line.split("=",1) for line in path.read_text().splitlines() if "=" in line and not line.startswith("#"))
    return values["loader_version"].strip(), values["fabric_version"].strip()


def prepare(args):
    run = args.run_dir.resolve()
    args.project = args.project.resolve()
    if not (args.project / "gradlew").is_file():
        raise ValueError("Point --project at a P2S checkout with gradlew")
    if args.quick_world and not re.fullmatch(r"[\w -]+", args.quick_world):
        raise ValueError("World name must be a single directory name")
    if args.bridge and not args.bridge.resolve().is_file():
        raise ValueError("The specified companion JAR does not exist")
    if args.width < 320 or args.height < 240 or args.gui_scale < 1:
        raise ValueError("Invalid window dimensions or GUI scale")
    os.umask(0o077)
    run.mkdir(mode=0o700, parents=True, exist_ok=True)
    run_lock = (run / ".client-lock").open("a")
    try:
        fcntl.flock(run_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        raise ValueError("Another client launcher owns this run directory") from exc
    # The launcher owns this directory, including display authority and optional fake credentials.
    if (run / "display.json").is_file():
        pid = json.loads((run / "display.json").read_text())["pid"]
        if Path("/proc", str(pid)).exists():
            raise ValueError("There is an active client associated with this run directory")
        (run / "display.json").unlink()
    java = java_home(args.java_home)
    game = private_game(run, args)
    gh = args.gradle_home.resolve()
    if not args.mod_jar:
        manifest = gradle_manifest(args, java, run, gh)
        (run / "classpath.json").write_text(json.dumps(manifest, indent=2)+"\n")
        runtime = development_args(manifest, args)
    else:
        from artifact_runtime import production_jvm_args
        loader, fabric = fabric_versions(args.project, args.version)
        runtime = production_jvm_args(version=args.version, mod_jar=args.mod_jar.resolve(), game_dir=game,
                                      gradle_home=gh, loader_version=loader, fabric_api_version=fabric)
    asset_dir, idx = assets(gh, args.version, args.cache.resolve(), args.assets, args.online)
    java_args = ["-Xms256M", "-Xmx1400M", "-XX:ActiveProcessorCount=3", "-Djava.awt.headless=false",
                 "-Duser.home=" + str(run / "profile"), "-Djava.net.useSystemProxies=false",
                 "-Dhttp.proxyHost=127.0.0.1", "-Dhttps.proxyHost=127.0.0.1", "-Dhttp.proxyPort=9",
                 "-Dhttps.proxyPort=9", "-Dhttp.nonProxyHosts=localhost|127.*|[::1]"]
    if args.bridge:
        java_args.extend(["-Dfabric.addMods=" + str(args.bridge.resolve()), "-Dp2s.acceptance.enabled=true",
                          "-Dp2s.acceptance.gameDir=" + str(game)])
    java_args += runtime + ["--username", "P2SAccept", "--uuid", "00000000000040008000000000000042",
                            "--accessToken", "0", "--version", args.version, "--versionType", "Fabric",
                            "--gameDir", str(game), "--assetsDir", str(asset_dir), "--assetIndex", idx,
                            "--width", str(args.width), "--height", str(args.height)]
    if args.quick_world:
        java_args.extend(["--quickPlaySingleplayer", args.quick_world])
    (run / "java.args").write_text("\n".join('"'+s.replace("\\","\\\\").replace('"','\\"')+'"' for s in java_args) + "\n")
    (run / "launch.json").write_text(json.dumps({"java":str(java/"bin"/"java"), "project":str(args.project), "version":args.version,
          "width":args.width,"height":args.height,"guiScale":args.gui_scale,"assets":args.assets,"gameDir":str(game)}, indent=2))
    if args.prepare_only:
        print(f"Prepared Minecraft {args.version}: {run / 'launch.json'}")
        return
    xvfb = shutil.which("xvfb-run")
    if not xvfb:
        raise ValueError("xvfb-run and xauth are required")
    command = [xvfb, "-a", "-n", "150", "-s",
               f"-screen 0 {args.width}x{args.height}x24 +extension GLX +extension XTEST -nolisten tcp",
               sys.executable, str(Path(__file__).resolve()), "_inside", str(run)]
    display_file = run / "display.json"
    child = subprocess.Popen(command, start_new_session=True)
    def stop(signum, frame):
        try:
            if display_file.is_file():
                pid = json.loads(display_file.read_text())["pid"]
                if os.getsid(pid) == child.pid:
                    os.kill(pid, signal.SIGTERM)
                    return
            os.killpg(child.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
    old_handlers = {sig:signal.signal(sig, stop) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        status = child.wait()
    finally:
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        display_file.unlink(missing_ok=True)
        run_lock.close()
    if status:
        raise ValueError(f"Minecraft exited with status {status}; see {run / 'client.log'}")



def inside(run):
    metadata = json.loads((run / "launch.json").read_text())
    os.chdir(metadata["gameDir"])
    (run / "display.json").write_text(json.dumps({"display":os.environ["DISPLAY"], "authority":os.environ["XAUTHORITY"], "pid":os.getpid()}))
    os.environ.update(LIBGL_ALWAYS_SOFTWARE="1", LP_NUM_THREADS="3", ALSOFT_DRIVERS="null")
    with (run / "client.log").open("w") as log:
        os.dup2(log.fileno(), 1)
        os.dup2(log.fileno(), 2)
    os.execv(metadata["java"], [metadata["java"], "@"+str(run / "java.args")])


def main():
    if len(sys.argv)==3 and sys.argv[1]=="_inside":
        inside(Path(sys.argv[2]))
        return
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--project", type=Path, required=True)
    p.add_argument("--version", choices=("1.21","1.21.1"), required=True)
    p.add_argument("--run-dir", required=True, type=Path)
    p.add_argument("--cache", required=True, type=Path, help="Private persistent directory for Mojang assets")
    p.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    p.add_argument("--gradle-home", type=Path, default=Path(os.environ.get("GRADLE_USER_HOME",Path.home()/".gradle")))
    source=p.add_mutually_exclusive_group()
    source.add_argument("--mod-jar", type=Path, help="Test this exact deployable JAR in the production namespace")
    source.add_argument("--classpath-manifest", type=Path, help="Reuse a manifest generated from the same checkout and version")
    p.add_argument("--bridge", type=Path, help="Optional companion JAR for the selected namespace and version")
    p.add_argument("--config", type=Path, help="Synthetic p2s_client.json used only in this gameDir")
    p.add_argument("--world-template", type=Path)
    p.add_argument("--quick-world", help="World in the private run/game/saves directory")
    p.add_argument("--width", type=int, default=1280)
    p.add_argument("--height", type=int, default=720)
    p.add_argument("--gui-scale", type=int, default=2)
    p.add_argument("--lang", choices=("en_us","zh_cn"), default="en_us")
    p.add_argument("--assets", choices=("visual","full"), default="visual")
    p.add_argument("--prepare-only", action="store_true", help="Verify and prepare the runtime without opening a window")
    p.add_argument("--online", action="store_true", help="Permit Gradle downloads and official, hash-verified Minecraft assets")
    args=p.parse_args()
    try:
        prepare(args)
    except (ValueError,OSError,KeyError,subprocess.CalledProcessError) as exc:
        p.exit(1, str(exc)+"\n")


if __name__ == "__main__":
    main()
