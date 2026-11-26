"""Exercise the installed P2S mod inside a disposable singleplayer world using the acceptance companion."""
import argparse
import json
import math
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import threading
from urllib import error, parse, request
import zipfile

BLOCKS = ['minecraft:gold_block', 'minecraft:diamond_block']


class Game:
    def __init__(self, game_dir):
        self.game_dir = game_dir
        info = json.loads((game_dir/'.p2s-acceptance-ready.json').read_text())
        self.url = info['baseUrl'].rstrip('/')
        address = parse.urlsplit(self.url)
        if address.scheme != 'http' or address.hostname != '127.0.0.1' or address.username:
            raise ValueError('The acceptance companion must be on the loopback interface.')
        self.token = info['token']
        self.http = request.build_opener(request.ProxyHandler({}))

    def require_fixture(self):
        config = json.loads((self.game_dir/'config/p2s_client.json').read_text())
        self.fixture_key = config['apiKey']
        if config.get('model') != 'p2s-local-fixture' or parse.urlsplit(config['apiUrl']).hostname != '127.0.0.1':
            raise ValueError('This suite requires the disposable local fixture provider.')

    def call(self, route, data=None):
        headers = {'Authorization':'Bearer ' + self.token, 'Content-Type':'application/json'}
        req = request.Request(self.url + route, headers=headers, data=json.dumps(data).encode() if data is not None else None)
        try:
            with self.http.open(req, timeout=20) as r:
                result = json.load(r)
        except error.HTTPError as exc:
            raise RuntimeError(f'{req.get_method()} {route} HTTP {exc.code}: {exc.read(600).decode(errors="replace")}') from exc
        if result.get('ok') is False or ('receipt' in result and result['receipt'].get('ok') is False):
            raise RuntimeError(f'{route} was rejected: {result}')
        return result

    def state(self):
        return self.call('/state')

    def action(self, action_name, **fields):
        return self.call('/action', dict(fields, action=action_name))

    def block(self, pos):
        return self.call('/world/block?' + parse.urlencode(dict(zip(('x','y','z'),pos))))['block']

    def wait(self, description, predicate, timeout=30, fail_on_agent_error=False):
        end = time.monotonic() + timeout
        while True:
            state = self.state()
            if predicate(state):
                return state
            if fail_on_agent_error and not state.get('busy') and state.get('status') == 'error':
                raise RuntimeError(f'{description}: game reported error: {state.get("lastAssistant","")}')
            if time.monotonic() >= end:
                summary = {k:state.get(k) for k in ('screen','status','busy','pendingPaths','selectedPath','pendingPath','lastAssistant')}
                raise TimeoutError(f'{description}: {summary}')
            time.sleep(.15)


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.game = Game(args.game_dir)
        self.game.require_fixture()
        self.tag = secrets.token_hex(4)
        self.out = args.output
        self.out.mkdir(parents=True, exist_ok=False)
        self.steps = []
        self.pictures = []
        self.artifact = {}

    def save(self):
        data = {'scenario':self.tag,'target':self.artifact,'steps':self.steps,'screenshots':self.pictures}
        temp = self.out/'report.partial'
        temp.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
        os.replace(temp,self.out/'report.json')

    def check(self, name, condition, **evidence):
        self.steps.append(dict(name=name, passed=bool(condition), **evidence))
        self.save()
        if not condition:
            raise AssertionError(f'{name}: {evidence}')

    def picture(self, name):
        if self.args.capture_script:
            time.sleep(.35)
            path = self.out/(name + '.png')
            subprocess.run([sys.executable, str(self.args.capture_script), '--run-dir', str(self.args.run_dir),
                            'screenshot', str(path)], timeout=20, check=True)
            if not path.read_bytes().startswith(b'\x89PNG\r\n\x1a\n'):
                raise RuntimeError('screen capture did not produce a PNG')
            self.pictures.append(path.name)
            self.save()

    def run(self):
        g = self.game
        state = g.wait('singleplayer world and P2S server bridge', lambda s:s.get('worldReady') and s.get('bridgeReady'))
        self.artifact = {k:state.get(k) for k in ('mcVersion','modVersion','artifact','screen','guiWidth','guiHeight')}
        # Require an idle test instance. Do not approve or discard unrelated pending work.
        self.check('private world ready and idle', not state['busy'] and not state['pendingPaths'] and not state.get('choice'))
        player = state['player']
        a = [math.floor(player['x'])+2, math.floor(player['y'])+4, math.floor(player['z'])+2]
        b = [a[0]+7, a[1]+2, a[2]+2]
        cells = [[a[0]+i,a[1],a[2]] for i in range(4)]
        old = [g.block(p) for p in cells]
        self.check('sample cells distinguish real commits', all(v not in BLOCKS for v in old), cells=cells, before=old)
        g.action('selection', pos1=a, pos2=b)
        g.wait('selection received by the game', lambda s:s.get('pos1')==a and s.get('pos2')==b)
        name = 'Acceptance-'+self.tag
        g.action('project.create', name=name)
        state = g.wait('server acknowledges new project', lambda s:s.get('projectName')==name and s.get('projectId'))
        self.check('created a project in the chosen selection', True, project=state['projectId'], pos1=a, pos2=b)
        g.action('message',text='P2S-ACCEPT-PING-'+self.tag)
        g.wait('real mod/provider round trip', lambda s: not s['busy'] and 'ACK PING '+self.tag in str(s.get('lastAssistant')), fail_on_agent_error=True)
        self.check('chat reached the scripted provider and came back',True)
        self.picture('01-project-chat')
        start_exports = set((self.args.game_dir/'config/p2s_exports').glob('*.zip'))

        def pending(case):
            expected = [f'accept-{case.lower()}-{self.tag}-{i}.toml' for i in range(2)]
            g.action('message', text=f'P2S-ACCEPT-{case}-{self.tag}')
            state = g.wait('two files await review', lambda s: not s['busy'] and
                           {Path(p).name for p in s['pendingPaths']} == set(expected), timeout=45)
            return [next(p for p in state['pendingPaths'] if Path(p).name==e) for e in expected]

        def select(path):
            g.action('workspace.select', path=path)
            g.wait('selected server preview matches requested file',
                   lambda s:s.get('selectedPath')==path and s.get('pendingPath')==path)

        first, second = pending('BUILD')
        physical = [g.block(p) for p in cells[:2]]
        self.check('two changes are staged; server blocks retain original values', physical==old[:2],
                   paths=[first,second], blocks=physical)
        self.picture('02-two-staged')
        # Verify a diagnostic archive while both versions still exist.
        g.action('export')
        end = time.monotonic()+35
        while time.monotonic()<end:
            files = set((self.args.game_dir/'config/p2s_exports').glob('*.zip')) - start_exports
            if files: break
            time.sleep(.2)
        if len(files)!=1:
            raise RuntimeError(f'expected one newly completed diagnostic ZIP, got {len(files)}')
        path = files.pop()
        with zipfile.ZipFile(path) as z:
            names = z.namelist()
            required = {'session.json','transcript.md','env.txt','config.json','latest.log'}
            paths = [p for p in names if p.startswith('workspace/')]
            def version(kind,filename):
                return next((z.read(p) for p in paths if p.startswith('workspace/'+kind+'/') and p.endswith('/'+Path(filename).name)),b'')
            self.check('export contains both committed and staged versions for the same files',
                       required.issubset(names) and all(version('committed',f) and BLOCKS[i].encode() in version('staged',f)
                           and BLOCKS[i].encode() not in version('committed',f) for i,f in enumerate([first,second])),
                       workspaceEntries=paths)
            self.check('fixture API key redacted throughout exported contents',
                       self.game.fixture_key and not any(self.game.fixture_key.encode() in z.read(n) for n in names)
                       and json.loads(z.read('config.json')).get('apiKey')=='***')
            self.check('diagnostic transcript contains the original task and tool calls',
                       ('P2S-ACCEPT-PING-'+self.tag).encode() in z.read('transcript.md') and b'write' in z.read('transcript.md'))
        select(first)
        g.action('patch.apply',path=first)
        g.wait('first receipt with second file still pending', lambda s:not s['busy'] and s['pendingPaths']==[second])
        physical = [g.block(p) for p in cells[:2]]
        self.check('single apply changes only the selected file in the server world',physical==[BLOCKS[0],old[1]],blocks=physical)
        select(second)
        g.action('patch.discard',path=second,reason='acceptance test')
        g.wait('all review decisions acknowledged and model resumed', lambda s:not s['busy'] and not s['pendingPaths'] and
               'ACK BUILD' in str(s.get('lastAssistant')))
        self.check('discard leaves the second server block unchanged',g.block(cells[1])==old[1])
        pending('ALL')
        g.action('patch.apply_all')
        g.wait('apply-all has two receipts',lambda s:not s['busy'] and not s['pendingPaths'] and 'ACK ALL' in str(s.get('lastAssistant')))
        physical = [g.block(p) for p in cells[2:]]
        self.check('apply-all changes both server blocks',physical==BLOCKS,blocks=physical)
        self.picture('03-applied')
        g.action('message',text='P2S-ACCEPT-CHOICE-'+self.tag)
        state = g.wait('choice shown',lambda s:s.get('choice') and s['choice'].get('options'))
        options = [o['id'] for o in state['choice']['options']]
        self.check('choice exposes two requested options',set(options)=={'wood','stone'},options=options)
        self.picture('04-choice')
        g.action('choice',id='wood')
        g.wait('choice round trip',lambda s:not s['busy'] and not s.get('choice') and 'ACK CHOICE' in str(s.get('lastAssistant')))
        self.check('selected option delivered and conversation resumed',True)
        self.picture('05-choice-resolved')

    def finish(self):
        try:
            self.run()
        except Exception as exc:
            self.steps.append({'name':'suite stopped','passed':False,'error':str(exc)})
            self.save()
            if self.args.capture_script:
                try: self.picture('failure')
                except Exception as capture_error: print('failure capture:',capture_error,file=sys.stderr)
            raise
        finally:
            self.save()


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir',required=True,type=Path)
    parser.add_argument('--run-dir',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path,help='New evidence directory (refused if already present)')
    parser.add_argument('--capture-script',type=Path)
    parser.add_argument('--serve-fixture',action='store_true',help='Own the loopback provider during this run, using the existing test configuration')
    args=parser.parse_args()
    provider=None
    if args.serve_fixture:
        from fixture_provider import serve_existing_fixture
        provider=serve_existing_fixture(args.game_dir,args.run_dir/'fixture')
        thread=threading.Thread(target=provider.serve_forever,daemon=True)
        thread.start()
    try:
        Acceptance(args).finish()
    finally:
        if provider:
            provider.shutdown()
            provider.server_close()
