"""Scripted model replies for optional in-game delegation and compaction acceptance."""
import json
import re
import threading

MARK = re.compile(r'^P2S-ADV-(FIRST|DELEGATE|CHILD|PAD|RECENT|CHECK)-([a-z0-9]{6,24})$')
PIN = re.compile(r'P2S-ADV-FIRST-([a-z0-9]{6,24})')
SUMMARY_MARKER = '[P2S_CONTEXT_COMPACTION_SUMMARY]'
READ_REQUIRED = {'ls', 'read', 'inspect_world'}
MUTATING = {'write', 'edit', 'set_part', 'move', 'delete', 'sh', 'script', 'task', 'ask_user', 'task_cancel'}
_gates = {}
_lock = threading.Lock()


def gate(tag):
    with _lock:
        return _gates.setdefault(tag, threading.Event())


def text(content):
    return {'role':'assistant', 'content':content}, 'stop'


def call(identifier, name, args):
    return {'role':'assistant', 'content':None, 'tool_calls':[{
        'id':identifier, 'type':'function', 'function':{'name':name, 'arguments':json.dumps(args)}
    }]}, 'tool_calls'


def results(history):
    out = {}
    for msg in history:
        if msg.get('role') != 'tool':
            continue
        try:
            value = json.loads(msg.get('content',''))
        except (TypeError, ValueError):
            continue
        if isinstance(value, dict):
            out[msg.get('tool_call_id')] = value
    return out


def read_only(names):
    return READ_REQUIRED.issubset(names) and names.isdisjoint(MUTATING)


def child(body, history, tag):
    advertised = {t.get('function',{}).get('name','') for t in body.get('tools',[])}
    actual = results(history)
    ls_id = 'adv_child_ls_' + tag
    if not read_only(advertised):
        return text('FAIL CHILD wrong tool permissions')
    if ls_id not in actual:
        return call(ls_id, 'ls', {'path':'workspace'})
    ls = actual[ls_id]
    if ls.get('ok') is not True or not isinstance(ls.get('entries'),list):
        return text('FAIL CHILD read-only workspace operation')
    # Keep the child active until a real task_status(wait=true) returned timed_out.
    # The waiter and child are independent HTTP handlers in ThreadingHTTPServer.
    if not gate(tag).wait(18):
        return text('FAIL CHILD no observed parent timeout')
    return text('CHILD_OK ' + tag + ' tools=' + ','.join(sorted(advertised)))


def delegate(history, tag):
    ids = {p: 'adv_' + p + '_' + tag for p in ('launch','short','final')}
    actual = results(history)
    if ids['launch'] not in actual:
        return call(ids['launch'], 'task', {'task':'P2S-ADV-CHILD-' + tag, 'profile_id':'general-planner', 'skill_ids':[]})
    launch = actual[ids['launch']]
    task_id = launch.get('subagent_id')
    capabilities = set(launch.get('effective_tools', []))
    if not launch.get('ok') or not task_id or launch.get('profile_id') != 'general-planner' or not read_only(capabilities):
        return text('FAIL DELEGATE launch receipt or permissions')
    if ids['short'] not in actual:
        return call(ids['short'], 'task_status', {'id':task_id,'wait':True,'timeout_ms':150})
    short = actual[ids['short']]
    if short.get('ok') is not True or short.get('timed_out') is not True:
        return text('FAIL DELEGATE missing active-child timeout')
    gate(tag).set()
    if ids['final'] not in actual:
        return call(ids['final'], 'task_status', {'id':task_id,'wait':True,'timeout_ms':12000})
    final = actual[ids['final']]
    expected_child = 'CHILD_OK ' + tag + ' tools=' + ','.join(sorted(capabilities))
    if not (final.get('ok') and final.get('timed_out') is False and final.get('status')=='completed'
            and final.get('id')==task_id and final.get('response')==expected_child):
        return text('FAIL DELEGATE final wait/child response/actual tool declarations')
    notice = '[subagent_notice] Subagent ' + task_id + ' (profile general-planner) finished with status completed.'
    if any(m.get('role')=='user' and notice in str(m.get('content','')) for m in history):
        with _lock:
            _gates.pop(tag,None)
        return text('ACK DELEGATE ' + tag)
    return text('Waiting for subagent_notice ' + tag)


def check_projection(messages, tag):
    first = 'P2S-ADV-FIRST-' + tag
    projected = [m for m in messages if m.get('role') != 'system']
    summaries = [m for m in projected if m.get('role')=='user' and
                 str(m.get('content','')).startswith(SUMMARY_MARKER)]
    recent = 'adv_recent_ls_' + tag
    pairs = [(a,b) for a,b in zip(projected,projected[1:]) if
             a.get('role')=='assistant' and any(c.get('id')==recent for c in (a.get('tool_calls') or [])) and
             b.get('role')=='tool' and b.get('tool_call_id')==recent]
    checks = {
        'pinned-first': bool(projected) and projected[0].get('role')=='user' and projected[0].get('content')==first,
        'summary': len(summaries)==1 and 'ADV-HANDOFF-' + tag in summaries[0]['content'],
        'archive-only-prefix': all('OLD-PADDING-' + tag not in str(m.get('content','')) for m in projected),
        'paired-recent-tool': len(pairs)==1 and results([pairs[0][1]]).get(recent,{}).get('ok') is True,
    }
    failed = [k for k,ok in checks.items() if not ok]
    return text(('FAIL CHECK ' + ','.join(failed) if failed else 'ACK CHECK') + ' ' + tag)


def advanced_reply(body):
    """Return a fixture response tuple, or None when the latest task belongs to the base suite."""
    messages = body.get('messages',[])
    if not messages:
        return None
    system = str(messages[0].get('content','')) if messages[0].get('role')=='system' else ''
    if 'Update the handoff using the previous summary' in system:
        data = '\n'.join(str(m.get('content','')) for m in messages if m.get('role')=='user')
        marker = PIN.search(data)
        if marker and 'Earlier conversation (JSON lines' in data:
            tag = marker[1]
            summary = ('Goal: retain the original task.\nConstraints & Preferences: isolated world.\n'
                       'Progress: delegation completed.\nKey Decisions: read-only planner.\nNext Steps: verify context.\n'
                       'Critical Context: ADV-HANDOFF-' + tag)
            msg, finish = text(summary)
            return 'ADV_SUMMARY', tag, msg, finish
    for i in range(len(messages)-1, -1, -1):
        m = messages[i]
        if m.get('role') != 'user':
            continue
        value = str(m.get('content', '')).strip()
        if value.startswith('P2S-ACCEPT-'):
            return None
        mark = MARK.fullmatch(value)
        if mark is None:
            continue
        case,tag = mark.groups()
        history = messages[i+1:]
        if case=='CHILD':
            msg,finish = child(body,history,tag)
        elif case=='DELEGATE':
            msg,finish = delegate(history,tag)
        elif case=='PAD':
            msg,finish = text('ACK PAD ' + tag + ' OLD-PADDING-' + tag + ' ' + ('archive word ' * 1800))
        elif case=='RECENT':
            ident = 'adv_recent_ls_' + tag
            observed = results(history)
            if ident not in observed:
                msg,finish = call(ident,'ls',{'path':'workspace'})
            else:
                msg,finish = text(('ACK RECENT ' if observed[ident].get('ok') else 'FAIL RECENT ') + tag)
        elif case=='CHECK':
            msg,finish = check_projection(messages,tag)
        else:
            msg,finish = text('ACK FIRST ' + tag)
        return 'ADV_' + case, tag, msg, finish
    return None
