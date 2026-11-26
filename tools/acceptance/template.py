#!/usr/bin/env python3
"""Create a deterministic superflat world via the game's own GUI, or export the saved private world as a reusable template."""
import argparse
import fcntl
import json
from pathlib import Path
import re
import shutil
import tempfile
import time
from ui import Window


def wait_until(check, timeout, message):
    start = time.monotonic()
    while not check():
        if time.monotonic() - start > timeout:
            raise RuntimeError(message)
        time.sleep(.5)


def has_log(path, message):
    return path.is_file() and message in path.read_text(errors="replace")


def create(run, name, seed):
    launch = json.loads((run / "launch.json").read_text())
    if not (launch["height"] == 720 and launch["guiScale"] == 2 and launch["width"] >= 980):
        raise ValueError("Template generation uses the 720px-high, GUI-scale-2 world creation screen; use the default window")
    game = run / "game"
    if (game / "saves").exists() and any((game / "saves").iterdir()):
        raise ValueError("Template generation requires an empty saves directory")
    log = run / "client.log"
    wait_until(lambda: (run / "display.json").exists() and has_log(log, "minecraft:textures/atlas/gui.png-atlas"),
               180, "Minecraft title screen did not initialize; inspect client.log")
    time.sleep(3)
    ui = Window(run)
    try:
        centre = ui.width // 2
        def click(dx, y):
            ui.click(centre + dx, y, 1)
            time.sleep(.35)
        click(0, 296)  # Singleplayer, which opens the creation wizard for a fresh gameDir.
        time.sleep(5)
        click(0, 156)
        ui.chord(["Control_L", "a"])
        ui.type_text(name)
        click(0, 212)
        click(0, 212)  # Survival → Hardcore → Creative; commands enabled by vanilla.
        click(0, 28)  # World tab.
        click(-140, 130)  # Default → Superflat.
        click(266, 276)  # Disable structures.
        click(0, 212)
        ui.type_text(str(seed))
        ui.screenshot(run / "template-world-settings.png")
        click(-155, 688)
        wait_until(lambda: has_log(log, "P2SAccept joined the game"), 200, "World did not load; inspect client.log and template-world-settings.png")
        time.sleep(2)
        ui.screenshot(run / "template-world.png")
        ui.chord(["Escape"])
        time.sleep(.7)
        click(0, 408)  # Save and Quit to Title.
        wait_until(lambda: has_log(log, "All dimensions are saved")
                   and (game / "saves" / name / "level.dat").is_file(), 60, "World save did not complete")
    finally:
        ui.close()


def export(run, name, target):
    source = run / "game" / "saves" / name
    if not (source / "level.dat").is_file() or source.is_symlink():
        raise ValueError(f"No saved acceptance world at {source}")
    if target.exists():
        raise ValueError("Template destination already exists; choose a fresh path")
    target.parent.mkdir(parents=True, exist_ok=True)
    with (source / "session.lock").open("rb") as lock:
        try:
            fcntl.lockf(lock, fcntl.LOCK_SH | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise ValueError("The integrated server still owns the world; save and quit to title first") from error
        with tempfile.TemporaryDirectory(dir=target.parent, prefix=".template-") as tmp:
            prepared = Path(tmp) / "world"
            shutil.copytree(source, prepared, ignore=shutil.ignore_patterns("session.lock"))
            (prepared / ".p2s-world-template").write_text(json.dumps({"name":name,
                "version":json.loads((run / "launch.json").read_text())["version"]}) + "\n")
            prepared.rename(target)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-dir", type=Path, required=True)
    sub = p.add_subparsers(dest="action", required=True)
    new = sub.add_parser("create")
    new.add_argument("--seed", type=int, default=20261126)
    new.add_argument("--name", default="P2SAccept")
    save = sub.add_parser("export")
    save.add_argument("--name", default="P2SAccept")
    save.add_argument("--to", type=Path, required=True)
    args = p.parse_args()
    run = args.run_dir.resolve()
    if not re.fullmatch(r"[A-Za-z0-9_-]+", args.name) or not (run/"game"/".p2s-acceptance-world-fixture").is_file():
        p.error("A fixture game directory and a simple ASCII world name are required")
    if args.action == "create":
        create(run, args.name, args.seed)
    else:
        export(run, args.name, args.to.resolve())


if __name__ == "__main__":
    main()
