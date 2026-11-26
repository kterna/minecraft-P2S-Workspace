"""Launch a fresh MC instance, exercise it, retain evidence, and close only that instance."""
import argparse
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace

from artifact_runtime import verify_origin
from extended_suite import ExtendedAcceptance
from fixture_provider import prepare_fixture
from inspect_screen import inspect
from world_suite import Acceptance, Game

HERE = Path(__file__).resolve().parent


def revision(project):
    def git(*args):
        return subprocess.check_output(['git','-C',str(project),*args],text=True).strip()
    return {'checkout':str(project),'commit':git('rev-parse','HEAD'),
            'modified':bool(git('status','--porcelain'))}


def wait_for_world(proc, run, timeout):
    end = time.monotonic() + timeout
    latest = 'waiting for the acceptance bridge'
    while proc.poll() is None and time.monotonic() < end:
        if (run/'game/.p2s-acceptance-ready.json').is_file():
            game = Game(run/'game')
            try:
                state = game.state()
                if state.get('worldReady') and state.get('bridgeReady') and not state.get('screen'):
                    return game, state
                latest = f"screen={state.get('screen')} world={state.get('worldReady')} bridge={state.get('bridgeReady')}"
            except (OSError,RuntimeError) as exc:
                latest = str(exc)
        time.sleep(.35)
    raise RuntimeError(f'Client did not enter the test world: exit={proc.poll()}, {latest}. See {run}/client.log and launcher.log')


def ui(run, *args):
    subprocess.run([sys.executable,str(HERE/'ui.py'),'--run-dir',str(run),*map(str,args)],check=True,timeout=15)


def stop(proc, run):
    if proc.poll() is not None:
        return 'exited'
    try:
        ui(run,'quit')
        proc.wait(timeout=35)
        return 'saved and closed'
    except (subprocess.SubprocessError, OSError):
        # Only this process group was created by this invocation (xvfb-run, X server and JVM).
        if proc.poll() is None:
            os.killpg(proc.pid,signal.SIGTERM)
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            os.killpg(proc.pid,signal.SIGKILL)
            proc.wait()
        return 'terminated isolated process group'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True,help='Persistent private acceptance storage')
    parser.add_argument('--project',type=Path,required=True,help='P2S checkout that specifies the target Fabric versions')
    parser.add_argument('--version',required=True,choices=['1.21','1.21.1'])
    sources=parser.add_mutually_exclusive_group()
    sources.add_argument('--mod-jar',type=Path,help='Production mode, load exactly this JAR')
    sources.add_argument('--classpath-manifest',type=Path,help='Dev mode, reuse an already compiled manifest for this target')
    parser.add_argument('--bridge',type=Path,help='Override cached compatible acceptance companion')
    parser.add_argument('--template',type=Path,help='Override the clean synthetic world')
    parser.add_argument('--java-home',type=Path)
    parser.add_argument('--run-dir',type=Path,help='New private output directory; must not exist')
    parser.add_argument('--width',type=int,default=1280)
    parser.add_argument('--height',type=int,default=720)
    parser.add_argument('--gui-scale',type=int,default=2)
    parser.add_argument('--lang',choices=['en_us','zh_cn'],default='en_us')
    parser.add_argument('--stream',action='store_true')
    parser.add_argument('--basic-only',action='store_true',help='Run only the building, review, export and choice scenario')
    parser.add_argument('--startup-timeout',type=float,default=300)
    args=parser.parse_args()
    os.umask(0o077)
    root=args.root.resolve()
    cache=root/'cache'
    project=args.project.resolve(strict=True)
    java=args.java_home or cache/'jdk21'
    variant='-intermediary' if args.mod_jar else ''
    bridge=(args.bridge or cache/'bridge'/f'p2s-acceptance-bridge-{args.version}{variant}.jar').resolve(strict=True)
    template=args.template or root/'worlds'/args.version/'P2SAccept'
    if args.template is None and not template.exists() and args.version=='1.21.1':
        # Both target versions can open a fresh copy of the older 1.21 superflat template.
        template=root/'worlds/1.21/P2SAccept'
    template=template.resolve(strict=True)
    if args.run_dir:
        run=args.run_dir.resolve()
        run.mkdir(mode=0o700,parents=True,exist_ok=False)
    else:
        (root/'runs').mkdir(parents=True,exist_ok=True)
        stamp=datetime.now().strftime('%Y%m%d-%H%M%S')
        run=Path(tempfile.mkdtemp(prefix=f'{stamp}-mc{args.version}-',dir=root/'runs'))
    meta={'status':'starting','target':revision(project),'version':args.version,
          'mode':'release' if args.mod_jar else 'development','evidence':str(run)}
    if args.mod_jar:
        with args.mod_jar.open('rb') as jar:
            meta['jar']={'source':str(args.mod_jar.resolve()),'sha256':hashlib.file_digest(jar,'sha256').hexdigest()}
    meta['bridge']=bridge.name
    def save():
        tmp=run/'acceptance.partial'
        tmp.write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n')
        os.replace(tmp,run/'acceptance.json')
    save()
    print(f'Evidence: {run}',flush=True)
    provider=prepare_fixture(run/'seed',run/'fixture',args.stream)
    service=threading.Thread(target=provider.serve_forever,daemon=True)
    service.start()
    proc=None
    try:
        command=[sys.executable,str(HERE/'client.py'),'--project',str(project),'--version',args.version,'--bridge',str(bridge),
                 '--cache',str(cache),'--java-home',str(java),'--run-dir',str(run),'--world-template',str(template),
                 '--config',str(run/'seed/config/p2s_client.json'),'--width',str(args.width),'--height',str(args.height),
                 '--gui-scale',str(args.gui_scale),'--lang',args.lang]
        if args.mod_jar:
            command+=['--mod-jar',str(args.mod_jar.resolve(strict=True))]
        elif args.classpath_manifest:
            command+=['--classpath-manifest',str(args.classpath_manifest.resolve(strict=True))]
        with (run/'launcher.log').open('w') as launch_log:
            proc=subprocess.Popen(command,stdout=launch_log,stderr=subprocess.STDOUT,start_new_session=True)
        game,state=wait_for_world(proc,run,args.startup_timeout)
        meta['runtime']={k:state.get(k) for k in ('mcVersion','modVersion','development','namespace','codeSourceName','artifactNames','windowWidth','windowHeight','guiWidth','guiHeight')}
        if state.get('mcVersion')!=args.version or state.get('development') != (args.mod_jar is None):
            raise RuntimeError(f'Unexpected runtime identity: {meta["runtime"]}')
        if args.mod_jar:
            meta['verifiedOrigin']=verify_origin(run)
        save()
        ui(run,'key','o')
        state=game.wait('physical O opens the P2S panel',lambda s:s['screen'].endswith('P2SChatScreen'))
        ui(run,'screenshot',run/'panel.png')
        (run/'panel-layout.json').write_text(json.dumps(inspect(state),ensure_ascii=False,indent=2)+'\n')
        classes=[('core',Acceptance)] if args.basic_only else [('core',Acceptance),('extended',ExtendedAcceptance)]
        for name,scenario in classes:
            meta['status']='running '+name
            save()
            scenario(SimpleNamespace(game_dir=run/'game',run_dir=run,output=run/name,capture_script=HERE/'ui.py')).finish()
        meta['status']='passed'
    except Exception as err:
        meta.update(status='failed',error=str(err))
        print(str(err),file=sys.stderr)
    finally:
        try:
            if proc is not None:
                meta['shutdown']=stop(proc,run)
                meta['clientExit']=proc.returncode
        finally:
            provider.shutdown()
            provider.server_close()
            audit = run/'fixture/requests.jsonl'
            meta['providerRequests'] = len(audit.read_text().splitlines()) if audit.is_file() else 0
            save()
    print(f"{meta['status']}: {run/'acceptance.json'}",flush=True)
    return 0 if meta['status']=='passed' else 1


if __name__=='__main__':
    sys.exit(main())
