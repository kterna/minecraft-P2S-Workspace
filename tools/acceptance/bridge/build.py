#!/usr/bin/env python3
"""Build a separate acceptance-only Fabric companion in the chosen Minecraft namespace."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version', choices=['1.21', '1.21.1'], required=True)
parser.add_argument('--classpath-file', required=True, type=Path,
                    help='A named Loom/P2S classpath, or a Gradle worker -cp argument file')
parser.add_argument('--output', type=Path, default=Path(__file__).resolve().parent / 'build')
parser.add_argument('--mappings', type=Path, help='Tiny v2 mappings with named and intermediary namespaces')
parser.add_argument('--remapper-classpath-file', type=Path, help='Classpath for net.fabricmc.tinyremapper.Main')
args = parser.parse_args()
if bool(args.mappings) != bool(args.remapper_classpath_file):
    parser.error('Provide both --mappings and --remapper-classpath-file to build an intermediary companion')

def read_classpath(path):
    data = path.read_text().strip().splitlines()
    if len(data) == 2 and data[0].strip() in ('-cp', '-classpath', '--class-path'):
        return data[1].strip()
    if len(data) == 1 and data[0].strip():
        return data[0].strip()
    parser.error(f'Expected a classpath or a two-line -cp file: {path}')

jdk_bin = Path(os.environ['JAVA_HOME']) / 'bin' if 'JAVA_HOME' in os.environ else None
root = Path(__file__).resolve().parent
classpath = read_classpath(args.classpath_file)
args.output.mkdir(parents=True, exist_ok=True)
jar = args.output / f'p2s-acceptance-bridge-{args.version}.jar'
with tempfile.TemporaryDirectory(dir=args.output) as temp:
    classes = Path(temp) / 'classes'
    classes.mkdir()
    subprocess.run([
        str(jdk_bin / 'javac') if jdk_bin else 'javac', '--release', '21', '-proc:none',
        '--add-modules', 'jdk.httpserver', '-cp', classpath, '-d', str(classes),
        str(root / 'src/main/java/com/p2s/AcceptanceBridge.java')
    ], check=True)
    archive_path = Path(temp) / jar.name
    with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED) as archive:
        for p in sorted(classes.rglob('*.class')):
            archive.write(p, p.relative_to(classes))
        archive.write(root / 'src/main/resources/fabric.mod.json', 'fabric.mod.json')
    archive_path.replace(jar)
    print(jar, flush=True)
    if args.mappings:
        # Inherited game member names require the named Minecraft classes, including the
        # separate client-only jar in a split Loom project. Remapping the entire test runtime
        # (JUnit, every mod and server library) exhausts memory and adds no game mappings.
        members = ['net/minecraft/client/Minecraft.class', 'net/minecraft/world/level/Level.class',
                   'com/p2s/ClientAgentManager.class', 'com/p2s/P2SMod.class']
        provided = []
        for entry in classpath.split(os.pathsep):
            path = Path(entry)
            if path.is_dir() and any((path / member).is_file() for member in members):
                provided.append(str(path))
            elif path.is_file() and path.suffix == '.jar':
                with zipfile.ZipFile(path) as dep:
                    if any(member in dep.NameToInfo for member in members):
                        provided.append(str(path))
        final = jar.with_name(f'{jar.stem}-intermediary.jar')
        temp_output = Path(temp) / final.name
        subprocess.run([
            str(jdk_bin / 'java') if jdk_bin else 'java', '-Xmx768m', '-XX:ActiveProcessorCount=2',
            '-cp', read_classpath(args.remapper_classpath_file), 'net.fabricmc.tinyremapper.Main',
            str(jar), str(temp_output), str(args.mappings), 'named', 'intermediary', *provided
        ], check=True)
        temp_output.replace(final)
        print(final)
