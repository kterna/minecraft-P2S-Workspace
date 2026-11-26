"""Inspect a private game or interact with its real screen at a widget's current location."""
import argparse
import json
from pathlib import Path
import subprocess
import sys
from world_suite import Game


def click_widget(args, state):
    candidates = [w for w in state.get('widgets',[]) if w.get('active') and w.get('visible')
                  and w.get('label','').casefold() == args.label.casefold()]
    if args.index is None and len(candidates) != 1:
        raise ValueError(f'{len(candidates)} active visible exact matches for {args.label!r}; inspect state and use --index for duplicates')
    if not candidates or (args.index is not None and not 0 <= args.index < len(candidates)):
        raise ValueError('The requested widget does not exist or is not visible and active.')
    w = candidates[args.index or 0]
    cx, cy = w['x'] + w['width']/2, w['y'] + w['height']/2
    if not (0 <= cx < state['guiWidth'] and 0 <= cy < state['guiHeight']):
        raise ValueError('Widget center falls outside the game viewport.')
    # ui.py locates the actual game window on its private X server and applies the physical offset.
    x = round(cx * state['windowWidth']/state['guiWidth'])
    y = round(cy * state['windowHeight']/state['guiHeight'])
    command = [sys.executable, str(Path(__file__).with_name('ui.py')), '--run-dir', str(args.run_dir), 'click', str(x), str(y)]
    subprocess.run(command,check=True)
    return {'clicked':args.label,'physical':[x,y],'screen':state['screen']}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir',required=True,type=Path)
    commands=parser.add_subparsers(required=True,dest='cmd')
    commands.add_parser('state')
    blocks=commands.add_parser('block')
    blocks.add_argument('xyz',nargs=3,type=int)
    action=commands.add_parser('action',help='Invoke one documented acceptance action')
    action.add_argument('name')
    action.add_argument('fields',nargs='?',default='{}',help='JSON object, e.g. {"label":"test"}')
    click=commands.add_parser('click-widget',help='Physically click the center of a visible widget by exact label')
    click.add_argument('--run-dir',type=Path,required=True,help='same launcher run that owns this private X display')
    click.add_argument('--index',type=int,help='zero-based index for repeated labels')
    click.add_argument('label')
    args=parser.parse_args()
    game=Game(args.game_dir)
    if args.cmd=='state':
        result=game.state()
    elif args.cmd=='block':
        result={'xyz':args.xyz,'block':game.block(args.xyz)}
    elif args.cmd=='action':
        game.require_fixture()
        fields=json.loads(args.fields)
        if not isinstance(fields,dict) or 'action' in fields: parser.error('fields must be a JSON object of action parameters')
        result=game.action(args.name,**fields)
    else:
        result=click_widget(args,game.state())
    print(json.dumps(result,ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
