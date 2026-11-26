"""Optional live tests of P2S delegated reads, waits, notifications and manual compaction."""
import argparse
import json
from pathlib import Path
import threading

from fixture_provider import serve_existing_fixture
from world_suite import Acceptance


class AdvancedAcceptance(Acceptance):
    def message(self, case):
        marker = f'P2S-ADV-{case}-{self.tag}'
        self.game.action('message', text=marker)
        expected = f'ACK {case} {self.tag}'
        state = self.game.wait(f'{case} (fixture must support P2S-ADV)', lambda s:not s.get('busy') and
                (s.get('lastAssistant','').startswith(expected) or s.get('lastAssistant','').startswith('FAIL ')), timeout=45)
        self.check(f'{case} completed through the configured local provider',
                   state['lastAssistant'].startswith(expected), reply=state['lastAssistant'][:100], session=state['sessionId'])
        return state

    def run(self):
        g=self.game
        state=g.wait('isolated singleplayer bridge',lambda s:s.get('worldReady') and s.get('bridgeReady'))
        self.artifact={k:state.get(k) for k in ('mcVersion','modVersion','artifactNames','namespace','development','codeSourceName')}
        self.check('fixture is idle',not state['busy'] and not state['pendingPaths'] and not state.get('choice'))
        p=state['player']
        pos=[p['x']+1,p['y']+2,p['z']+1]
        end=[pos[0]+2,pos[1]+2,pos[2]+2]
        g.action('selection',pos1=pos,pos2=end)
        g.wait('selection acknowledged',lambda s:s.get('pos1')==pos and s.get('pos2')==end)
        name='Advanced-' + self.tag
        receipt=g.action('project.create',name=name)['receipt']
        g.wait('new project synchronized',lambda s:s.get('projectId')==receipt['id'])
        first=self.message('FIRST')
        sid=first['sessionId']
        self.message('DELEGATE')
        # The fixture verifies both receipts, their actual task id, the task_status deadline,
        # the terminal child response and the independently delivered subagent_notice.
        audit_path=self.args.run_dir/'fixture'/'requests.jsonl'
        audit=[json.loads(line) for line in audit_path.read_text().splitlines() if line.strip()]
        parent=[r for r in audit if r.get('tag')==self.tag and r.get('case')=='ADV_DELEGATE']
        child=[r for r in audit if r.get('tag')==self.tag and r.get('case')=='ADV_CHILD']
        main_calls=[t for r in parent for t in r.get('sent_tools',[])]
        child_calls=[t for r in child for t in r.get('sent_tools',[])]
        self.check('one read-only child; one short timeout and one completed wait; correlated notification',
                   main_calls==['task','task_status','task_status'] and child_calls==['ls'],
                   parentTools=main_calls,childTools=child_calls,parentRequests=len(parent),childRequests=len(child))
        self.message('PAD')
        self.message('RECENT')
        g.action('compact')
        session_file=self.args.game_dir/'config'/'p2s_sessions_v2'/'sessions'/(sid+'.json')
        summary_tag='ADV-HANDOFF-' + self.tag
        def stored():
            try:
                return json.loads(session_file.read_text())
            except (FileNotFoundError,json.JSONDecodeError):
                return {}
        def captured(s):
            if s.get('busy'):
                return False
            saved=stored()
            return saved.get('firstKept',0)>0 and summary_tag in saved.get('contextSummary','')
        g.wait('manual compact stored a nonempty checkpoint',captured,timeout=45)
        saved=stored()
        raw=saved['llmHistory']
        self.check('full archive retains the long earlier turn and the original task after compacting',
                   any(m.get('role')=='assistant' and 'OLD-PADDING-'+self.tag in str(m.get('content','')) for m in raw)
                   and any(m.get('role')=='user' and m.get('content')=='P2S-ADV-FIRST-'+self.tag for m in raw),
                   firstKept=saved['firstKept'],archivedMessages=len(raw))
        end_state=self.message('CHECK')
        self.check('post-compact model input pins first user turn, contains verified summary, and replays a complete recent tool pair',
                   end_state['sessionId']==sid and not end_state.get('pendingPaths'),session=sid,summaryMarker=summary_tag)
        self.picture('01-after-compact')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir',required=True,type=Path)
    parser.add_argument('--run-dir',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--capture-script',type=Path)
    parser.add_argument('--serve-fixture',action='store_true',help='Own the fixture at the already configured loopback URL')
    args=parser.parse_args()
    provider=None
    if args.serve_fixture:
        provider=serve_existing_fixture(args.game_dir,args.run_dir/'fixture')
        threading.Thread(target=provider.serve_forever,daemon=True).start()
    try:
        AdvancedAcceptance(args).finish()
    finally:
        if provider is not None:
            provider.shutdown()
            provider.server_close()
