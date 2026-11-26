"""Deterministic OpenAI Chat fixture for a private P2S game instance, using only the standard library."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

CASES = re.compile(r'P2S-ACCEPT-(PING|BUILD|ALL|DISCARD|CHOICE|PLAN|STOP|ERROR|TOOLS|RECALL)(?:-([a-z0-9]+))?')
BLOCKS = ('minecraft:gold_block', 'minecraft:diamond_block')


def file_args(case, tag, index):
    # Independent cells relative to the selected project origin. No world coordinates are assumed.
    x = (0 if case == 'BUILD' else 2 if case == 'ALL' else 4) + index
    name = f'accept-{case.lower()}-{tag}-{index}'
    return {'path': f'workspace/{name}.toml', 'content': f"""format_version = 1
[workspace]
name = "{name}"
type = "manual"
[[part]]
name = "sample"
priority = 0
[[part.action]]
type = "box"
block = "{BLOCKS[index]}"
from = [{x}, 0, 0]
to = [{x}, 0, 0]
mode = "solid"
"""}


def reply(body):
    messages = body['messages']
    case, tag, start = 'PING', 'sample', 0
    for i, item in enumerate(messages):
        if item.get('role') == 'user':
            matches = list(CASES.finditer(str(item.get('content', ''))))
            if matches:
                m = matches[-1]
                case, tag, start = m[1], m[2] or 'sample', i
    history = messages[start + 1:]
    answered = {call['id'] for m in history for call in (m.get('tool_calls') or [])}
    def tool_calls(items):
        calls = []
        for i, (name, args) in enumerate(items):
            identifier = f'fixture_{case}_{tag}_{i}'
            if identifier not in answered:
                calls.append({'id': identifier, 'type': 'function',
                              'function': {'name': name, 'arguments': json.dumps(args)}})
        if calls:
            return {'role': 'assistant', 'content': None, 'tool_calls': calls}, 'tool_calls'
        return {'role': 'assistant', 'content': f'ACK {case} {tag}'}, 'stop'

    if case in {'BUILD', 'ALL', 'DISCARD'}:
        msg, finish = tool_calls([('write', file_args(case, tag, i)) for i in range(2)])
    elif case == 'TOOLS':
        path = f'workspace/accept-all-{tag}-0.toml'
        script = ("const r = tools.read({path: " + json.dumps(path) + "}); "
                  "return r.content.indexOf('minecraft:gold_block') >= 0 ? 'VFS-PASS-' + (3 * 4) : 'VFS-FAIL'")
        msg, finish = tool_calls([('read', {'path': path, 'committed': True}),
                                  ('script', {'source': script}),
                                  ('sh', {'command': 'grep -c gold_block ' + path})])
        if finish == 'stop':
            results = {}
            for message in history:
                if message.get('role') == 'tool':
                    try:
                        data = json.loads(message.get('content', ''))
                    except (TypeError, ValueError):
                        continue
                    if isinstance(data, dict):
                        results[message.get('tool_call_id')] = data
            read, script_result, shell = [results.get(f'fixture_TOOLS_{tag}_{i}', {}) for i in range(3)]
            passed = {
                'read': read.get('ok') is True and read.get('version') == 'committed'
                        and 'minecraft:gold_block' in str(read.get('content', '')),
                'script': script_result.get('ok') is True and 'VFS-PASS-12' in str(script_result.get('output', ''))
                          and script_result.get('nested_call_count') == 1 and not script_result.get('nested_call_failures'),
                'sh': shell.get('ok') is True and shell.get('exit_code') == 0 and str(shell.get('stdout', '')).strip() == '1',
            }
            failed = [tool for tool, ok in passed.items() if not ok]
            msg['content'] = ('FAIL TOOLS ' + ','.join(failed) if failed else 'ACK TOOLS') + ' ' + tag
    elif case == 'RECALL':
        users = [str(m.get('content', '')) for m in messages[:start] if m.get('role') == 'user']
        previous = all(any(f'P2S-ACCEPT-{action}-{tag}' in text for text in users) for action in ('PLAN','TOOLS'))
        isolated = all(f'P2S-ACCEPT-PING-fresh{tag}' not in text for text in users)
        msg = {'role': 'assistant', 'content': ('ACK RECALL ' if previous and isolated else 'FAIL RECALL ') + tag}
        finish = 'stop'
    elif case == 'CHOICE':
        msg, finish = tool_calls([('ask_user', {'prompt':'Select a test material', 'options':[
            {'id':'stone','label':'Stone'}, {'id':'wood','label':'Wood'}]})])
    elif case == 'PLAN':
        msg, finish = tool_calls([('update_plan', {'plan':[{'step':'Check the selection','status':'completed'}]})])
    else:
        msg, finish = {'role': 'assistant', 'content': f'ACK {case} {tag}'}, 'stop'
    return case, tag, msg, finish


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        if self.path != '/v1/chat/completions':
            self.send_error(404)
            return
        if self.headers.get('Authorization') != 'Bearer ' + self.server.key:
            self.send_error(401)
            return
        try:
            length = int(self.headers.get('Content-Length', 0))
            if not 0 < length <= 8 * 1024 * 1024:
                self.send_error(413)
                return
            body = json.loads(self.rfile.read(length))
            case, tag, msg, finish = reply(body)
        except (ValueError, TypeError, KeyError):
            self.send_error(400)
            return
        audit = {'case':case, 'tag':tag, 'messages':len(body['messages']), 'stream':bool(body.get('stream')),
                 'finish':finish, 'tool_results':[
                     m.get('tool_call_id') for m in body['messages'] if m.get('role') == 'tool'],
                 'sent_tools':[c['function']['name'] for c in msg.get('tool_calls', [])]}
        with (self.server.run_dir/'requests.jsonl').open('a') as f:
            f.write(json.dumps(audit) + '\n')
        if case == 'ERROR':
            self.send_json({'error':{'message':'fixture rate limit'}}, 429)
            return
        if case == 'STOP':
            time.sleep(5)
        try:
            if body.get('stream'):
                self.send_response(200)
                self.send_header('Content-Type','text/event-stream')
                self.end_headers()
                delta = dict(msg)
                if 'tool_calls' in delta:
                    delta['tool_calls'] = [dict(c, index=i) for i,c in enumerate(delta['tool_calls'])]
                for d,reason in [(delta, None), ({},finish)]:
                    event = {'choices':[{'index':0,'delta':d,'finish_reason':reason}]}
                    self.wfile.write(('data: ' + json.dumps(event) + '\n\n').encode())
                self.wfile.write(b'data: [DONE]\n\n')
            else:
                self.send_json({'model':'p2s-local-fixture','choices':[{'index':0,'message':msg,'finish_reason':finish}]})
        except (BrokenPipeError,ConnectionResetError):
            pass  # Stop intentionally cancels a pending HTTP call.

    def send_json(self, data, status=200):
        content = json.dumps(data).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(content)))
        self.end_headers()
        self.wfile.write(content)


def serve_existing_fixture(game_dir, run_dir):
    """Resume a test fixture at the exact URL already configured in a running isolated client."""
    if not (game_dir / '.p2s-acceptance-world-fixture').is_file():
        raise ValueError('An explicitly marked isolated world is required.')
    cfg = json.loads((game_dir / 'config/p2s_client.json').read_text())
    url = urlsplit(cfg['apiUrl'])
    if (cfg.get('model') != 'p2s-local-fixture' or url.scheme != 'http' or url.hostname != '127.0.0.1'
            or url.path != '/v1/chat/completions' or not url.port or url.port < 1024):
        raise ValueError('Unexpected fixture configuration.')
    run_dir.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', url.port), Handler)
    server.key = cfg['apiKey']
    server.run_dir = run_dir
    return server


def prepare_fixture(game_dir, run_dir, stream=False):
    run_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    config = game_dir / 'config' / 'p2s_client.json'
    config.parent.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.run_dir = run_dir
    server.key = secrets.token_urlsafe(24)
    url = f'http://127.0.0.1:{server.server_port}/v1/chat/completions'
    config_json = {'apiUrl':url, 'apiKey':server.key, 'model':'p2s-local-fixture', 'useToolCall':True,
                   'useStreaming':stream, 'autoApplyPatch':False, 'toolset':'v2', 'llm':{'api':'openai-completions'}}
    with config.open('x') as f:  # never overwrite a real player's settings
        json.dump(config_json, f)
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir', type=Path, required=True, help='Private directory for this new test run')
    parser.add_argument('--game-dir', type=Path, required=True, help='New, isolated Minecraft game directory')
    parser.add_argument('--stream', action='store_true')
    args = parser.parse_args()
    os.umask(0o077)
    server = prepare_fixture(args.game_dir, args.run_dir, args.stream)
    url = f'http://127.0.0.1:{server.server_port}/v1/chat/completions'
    print(f'fixture ready on {url}', flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
