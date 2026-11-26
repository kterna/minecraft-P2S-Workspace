"""Optional follow-up to world_suite for an idle disposable Minecraft acceptance client."""
import argparse
import json
from pathlib import Path
import threading
import time

from fixture_provider import serve_existing_fixture
from world_suite import Acceptance, BLOCKS


class ExtendedAcceptance(Acceptance):
    def reply(self, case, tag=None):
        tag = tag or self.tag
        self.game.action('message', text=f'P2S-ACCEPT-{case}-{tag}')
        expected = f'ACK {case} {tag}'
        state = self.game.wait(f'fixture result for {case}', lambda s: not s.get('busy') and
                               (expected in s.get('lastAssistant', '') or
                                (f'FAIL {case}' in s.get('lastAssistant', '') and tag in s.get('lastAssistant', ''))))
        self.check(f'{case} response verified by the fixture', expected in state['lastAssistant'],
                   reply=state['lastAssistant'], session=state['sessionId'])
        return state

    def project(self, state):
        # Pick a fresh pair of empty air cells so that undo restores a known world state.
        g = self.game
        p = state['player']
        for dy in (8, 12, 16, 20):
            origin = [p['x'] + 4, p['y'] + dy, p['z'] + 4]
            cells = [[origin[0] + x, origin[1], origin[2]] for x in (2, 3)]
            if all(g.block(cell) == 'minecraft:air' for cell in cells):
                break
        else:
            raise RuntimeError('No test pair of air cells near the player; use an open disposable world')
        endpoint = [origin[0]+7, origin[1]+2, origin[2]+2]
        g.action('selection', pos1=origin, pos2=endpoint)
        g.wait('selection synchronized', lambda s:s.get('pos1') == origin and s.get('pos2') == endpoint)
        name = 'Extended-' + self.tag
        receipt = g.action('project.create', name=name)['receipt']
        g.wait('new isolated project', lambda s:s.get('projectName') == name and s.get('projectId') == receipt['id'])
        self.check('dedicated project and two known air cells', bool(receipt['id']), project=receipt['id'], cells=cells)
        return cells

    def apply_sample(self, cells):
        g = self.game
        g.action('message', text='P2S-ACCEPT-ALL-' + self.tag)
        state = g.wait('two staged files', lambda s:not s.get('busy') and len(s.get('pendingPaths', [])) == 2)
        targets = []
        for index in (0, 1):
            filename = f'accept-all-{self.tag}-{index}.toml'
            targets.append(next(p for p in state['pendingPaths'] if Path(p).name == filename))
        g.action('patch.apply_all')
        g.wait('both files committed', lambda s:not s.get('busy') and not s.get('pendingPaths')
               and 'ACK ALL ' + self.tag in s.get('lastAssistant', ''))
        self.check('committed samples can be queried in the integrated server',
                   [g.block(c) for c in cells] == list(BLOCKS), paths=targets)
        return targets[0]

    def history_actions(self, path, cells):
        g = self.game
        g.action('workspace.select', path=path)
        g.wait('workspace selected', lambda s:s.get('selectedPath') == path)
        label = 'fixture-history-' + self.tag
        g.action('checkpoint.create', label=label)
        state = g.wait('named checkpoint', lambda s:any(c.get('label') == label for c in s.get('checkpoints', [])))
        checkpoint = next(c for c in state['checkpoints'] if c['label'] == label)
        self.check('selected workspace received a named checkpoint', bool(checkpoint['id']) and bool(checkpoint['revision']),
                   checkpoint=checkpoint)
        g.action('undo')
        g.wait('undo changed the server world', lambda s:any(c['label'].startswith('undo:') for c in s.get('checkpoints', []))
               and g.block(cells[0]) == 'minecraft:air')
        self.check('undo reverted one file and retained its sibling', g.block(cells[1]) == BLOCKS[1], actual=[g.block(c) for c in cells])
        g.action('redo')
        g.wait('redo changed the server world', lambda s:any(c['label'].startswith('redo:') for c in s.get('checkpoints', []))
               and g.block(cells[0]) == BLOCKS[0])
        self.check('redo restored the committed server blocks', [g.block(c) for c in cells] == list(BLOCKS))
        self.picture('01-redone')

    def stop_and_resume(self):
        g = self.game
        g.action('message', text='P2S-ACCEPT-STOP-' + self.tag)
        log_path = self.args.run_dir/'fixture'/'requests.jsonl'
        def in_flight(s):
            if not s.get('busy') or not log_path.is_file():
                return False
            for line in reversed(log_path.read_text().splitlines()):
                try:
                    event = json.loads(line)
                except ValueError:
                    continue
                if event.get('case') == 'STOP' and event.get('tag') == self.tag:
                    return True
            return False
        g.wait('delayed HTTP request reached fixture while agent busy', in_flight)
        sent = time.monotonic()
        g.action('stop')
        state = g.wait('Stop acknowledged', lambda s:not s.get('busy') and s.get('status') in ('stopped','cancelled'))
        self.check('Stop returned the agent to idle', state.get('status') in ('stopped','cancelled'), status=state['status'])
        self.reply('PING', 'next' + self.tag)
        # STOP's provider response is artificially delayed 5s. Check again after its scheduled arrival.
        time.sleep(max(0, sent + 5.3 - time.monotonic()))
        latest = g.state()
        self.check('late provider reply did not replace the subsequent turn',
                   not latest['busy'] and latest['lastAssistant'] == 'ACK PING next' + self.tag)

    def restore(self, initial):
        g = self.game
        g.action('session.new')
        g.wait('old session cleared', lambda s:not s.get('busy') and s.get('sessionId') != initial)
        fresh = self.reply('PING', 'fresh' + self.tag)
        fresh_id = fresh['sessionId']
        self.check('new session has a different persisted identity', bool(fresh_id) and fresh_id != initial,
                   first=initial, second=fresh_id)
        g.action('session.restore', id=initial)
        g.wait('restored original project session', lambda s:not s.get('busy') and s.get('sessionId') == initial)
        restored = self.reply('RECALL')
        self.check('restored model sees its own history and excludes the other session',
                   restored.get('sessionId') == initial, restored=initial, excluded=fresh_id)
        self.check('restored UI retains the original plan item',
                   any(p.get('step') == 'Check the selection' for p in restored['planItems']),
                   planItems=restored['planItems'], expected='Check the selection', session=initial)

    def run(self):
        g = self.game
        state = g.wait('singleplayer P2S bridge', lambda s:s.get('worldReady') and s.get('bridgeReady'))
        self.artifact = {k:state.get(k) for k in ('mcVersion','modVersion','artifactNames','namespace','development','codeSourceName')}
        self.check('private instance ready and idle', not state['busy'] and not state['pendingPaths'] and not state.get('choice'))
        cells = self.project(state)
        first = self.reply('PLAN')
        self.check('completed plan item is visible in the actual session',
                   any(item.get('step') == 'Check the selection' and item.get('status') == 'completed'
                       for item in first['planItems']), plan=first['planItems'])
        path = self.apply_sample(cells)
        self.history_actions(path, cells)
        self.reply('TOOLS')
        self.check('read, nested sandbox script and virtual shell left the world unchanged',
                   not g.state()['pendingPaths'] and [g.block(c) for c in cells] == list(BLOCKS))
        self.stop_and_resume()
        self.restore(first['sessionId'])
        self.picture('02-restored')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', required=True, type=Path)
    parser.add_argument('--run-dir', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--capture-script', type=Path)
    parser.add_argument('--serve-fixture', action='store_true')
    args = parser.parse_args()
    provider = None
    if args.serve_fixture:
        provider = serve_existing_fixture(args.game_dir, args.run_dir/'fixture')
        threading.Thread(target=provider.serve_forever, daemon=True).start()
    try:
        ExtendedAcceptance(args).finish()
    finally:
        if provider is not None:
            provider.shutdown()
            provider.server_close()
