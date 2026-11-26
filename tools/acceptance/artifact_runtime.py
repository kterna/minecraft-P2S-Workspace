"""Launch the supplied Fabric mod JAR through the production Linux client.

The caller prepares an isolated game directory, Java 21, display and assets. This
module selects only the official game, Mojang Linux libraries and Fabric's
declared production libraries. The mod under test is loaded from gameDir/mods.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path
from urllib.parse import unquote, urlsplit


def digest(path: Path, algorithm: str = "sha256") -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, algorithm).hexdigest()


def cached_jar(modules: Path, coordinate: str, expected_sha1: str | None = None) -> Path:
    group, name, version, *classifier = coordinate.split(":")
    filename = "-".join([name, version, *classifier]) + ".jar"
    folder = modules / group / name / version
    candidates = sorted(folder.glob("*/" + filename))
    for jar in candidates:
        if digest(jar, "sha1") == (expected_sha1 or jar.parent.name.zfill(40)):
            return jar
    raise ValueError(f"Missing or invalid Gradle cache entry for {coordinate}: {folder}")


def metadata(jar: Path) -> dict:
    with zipfile.ZipFile(jar) as archive:
        return json.loads(archive.read("fabric.mod.json"))


def install(source: Path, target: Path) -> None:
    if target.exists():
        if digest(source) != digest(target):
            raise ValueError(f"Different artifact already present: {target}; use a fresh run")
        return
    # A failed copy must never leave a partial mod that Fabric might discover.
    with tempfile.NamedTemporaryFile(dir=target.parent, prefix=".install-", delete=False) as staged:
        name = Path(staged.name)
    try:
        shutil.copyfile(source, name)
        os.replace(name, target)
    finally:
        name.unlink(missing_ok=True)


def linux_library(library: dict) -> bool:
    rules = library.get("rules", [])
    keep = not rules
    for rule in rules:
        target = rule.get("os", {})
        if target.get("name", "linux") != "linux":
            continue
        if "version" in target and not re.search(target["version"], platform.release()):
            continue
        if "arch" in target and not re.search(target["arch"], platform.machine()):
            continue
        if any(rule.get("features", {}).values()):
            continue
        keep = rule["action"] == "allow"
    return keep


def production_jvm_args(*, version: str, mod_jar: Path, game_dir: Path, gradle_home: Path,
                        loader_version: str, fabric_api_version: str) -> list[str]:
    if sys.platform != "linux":
        raise ValueError("This acceptance launcher is configured for Linux")
    game_dir = Path(game_dir).resolve()
    run_dir = game_dir.parent
    mod_jar = Path(mod_jar).resolve(strict=True)
    gradle_home = Path(gradle_home).expanduser().resolve()
    modules = gradle_home / "caches/modules-2/files-2.1"
    loom = gradle_home / "caches/fabric-loom" / version
    version_info = json.loads((loom / "mojang_minecraft_info.json").read_text())
    game_jar = loom / "minecraft-client.jar"
    if version_info.get("id") != version or digest(game_jar, "sha1") != version_info["downloads"]["client"]["sha1"]:
        raise ValueError(f"Official Minecraft client verification failed for {version}")
    loader = cached_jar(modules, f"net.fabricmc:fabric-loader:{loader_version}")
    intermediary = cached_jar(modules, f"net.fabricmc:intermediary:{version}:v2")
    fabric_api = cached_jar(modules, f"net.fabricmc.fabric-api:fabric-api:{fabric_api_version}")
    with zipfile.ZipFile(loader) as archive:
        loader_libs = json.loads(archive.read("fabric-installer.json"))["libraries"]
    libraries = [
        cached_jar(modules, lib["name"], lib.get("sha1"))
        for lib in loader_libs["common"] + loader_libs["client"]
    ]
    libraries.extend(
        cached_jar(modules, lib["name"], lib["downloads"]["artifact"]["sha1"])
        for lib in version_info["libraries"] if linux_library(lib)
    )
    # Keep the allowlisted production classpath independent of every Gradle worker or dev output.
    classpath = list(dict.fromkeys([loader, intermediary, *libraries, game_jar]))
    mod = metadata(mod_jar)
    if not isinstance(mod.get("id"), str) or not mod["id"]:
        raise ValueError(f"Missing Fabric mod id in {mod_jar}")
    if metadata(fabric_api)["id"] != "fabric-api" or mod["id"] == "fabric-api":
        raise ValueError("Expected distinct mod under test and Fabric API dependency")
    mods = game_dir / "mods"
    mods.mkdir(parents=True, exist_ok=True)
    subject = mods / "subject.jar"
    install(mod_jar, subject)
    install(fabric_api, mods / fabric_api.name)
    sha = digest(subject)
    if sha != digest(mod_jar):
        raise ValueError("Installed mod bytes differ from the requested file")
    entrypoints = []
    for kind in ("preLaunch", "main", "client"):
        for entry in mod.get("entrypoints", {}).get(kind, []):
            value = entry if isinstance(entry, str) else entry.get("value", "")
            if value:
                entrypoints.append(value.split("::", 1)[0])
    manifest = {
        "mode": "production", "minecraft": version, "loader": loader_version, "fabric_api": fabric_api_version,
        "mod_id": mod["id"], "mod_version": mod.get("version", "unknown"), "entrypoint_classes": entrypoints,
        "input": str(mod_jar), "installed": str(subject), "sha256": sha, "classpath": [str(p) for p in classpath],
        "class_log": str(run_dir / "artifact-classes.log"),
    }
    (run_dir / "artifact.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return [
        "-Dfabric.development=false", f"-Dfabric.gameJarPath={game_jar}",
        f"-Xlog:class+load=info:file={manifest['class_log']}:none:filecount=0",
        "-cp", os.pathsep.join(map(str, classpath)), "net.fabricmc.loader.impl.launch.knot.KnotClient",
    ]


def verify_origin(run_dir: Path) -> dict:
    manifest = json.loads((run_dir / "artifact.json").read_text())
    installed = Path(manifest["installed"])
    if digest(installed) != manifest["sha256"]:
        raise ValueError("Installed mod changed after launch preparation")
    entrypoints = set(manifest["entrypoint_classes"])
    loaded, unexpected = {}, {}
    with Path(manifest["class_log"]).open() as stream:
        for line in stream:
            name, separator, source = line.rstrip().partition(" source: ")
            if not separator or name not in entrypoints:
                continue
            uri = urlsplit(source)
            if uri.scheme == "file" and Path(unquote(uri.path)).resolve() == installed.resolve():
                loaded[name] = source
            else:
                unexpected[name] = source
    if unexpected or not loaded:
        raise ValueError(f"Could not verify mod entrypoint from {installed}; observed {loaded | unexpected}")
    return {"mod_id": manifest["mod_id"], "sha256": manifest["sha256"], "loaded": loaded}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify the source JAR of loaded Fabric entrypoint classes")
    parser.add_argument("--run-dir", required=True, type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(verify_origin(args.run_dir), indent=2))
    except (OSError, ValueError, KeyError) as error:
        parser.exit(1, f"Artifact verification failed: {error}\n")
