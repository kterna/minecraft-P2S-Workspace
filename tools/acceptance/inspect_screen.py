"""Capture one real Minecraft frame and measure the visible widgets reported by the game."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from world_suite import Game


def inspect(state):
    width, height = state['guiWidth'], state['guiHeight']
    widgets = [w for w in state['widgets'] if w['visible'] and w['width'] > 0 and w['height'] > 0]
    clipped, overlaps = [], []
    for i, w in enumerate(widgets):
        l,t,r,b = w['x'],w['y'],w['x']+w['width'],w['y']+w['height']
        edges = {k:v for k,v in {'left':-l,'top':-t,'right':r-width,'bottom':b-height}.items() if v>0}
        if edges:
            clipped.append({'index':i,'label':w['label'],'outsideLogicalPixels':edges})
        if not w['active']:
            continue
        for j,v in enumerate(widgets[:i]):
            if not v['active']:
                continue
            dx = min(r,v['x']+v['width']) - max(l,v['x'])
            dy = min(b,v['y']+v['height']) - max(t,v['y'])
            if dx > 0 and dy > 0:
                overlaps.append({'indexes':[j,i],'labels':[v['label'],w['label']],'intersection':[dx,dy]})
    return {'screen':state['screen'],'guiWidth':width,'guiHeight':height,'widgets':widgets,
            'clippedWidgets':clipped,'overlapCandidates':overlaps}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-dir',type=Path,required=True)
    parser.add_argument('--game-dir',type=Path,required=True)
    parser.add_argument('--out',type=Path,required=True,help='New directory for frame.png and widgets.json')
    args=parser.parse_args()
    args.out.mkdir(parents=True,exist_ok=False)
    game=Game(args.game_dir)
    first=game.state()
    subprocess.run([sys.executable,str(Path(__file__).with_name('ui.py')),'--run-dir',str(args.run_dir),
                    'screenshot',str(args.out/'frame.png')],check=True,timeout=20)
    after=game.state()
    if (first['screen'],first['guiWidth'],first['guiHeight']) != (after['screen'],after['guiWidth'],after['guiHeight']):
        raise RuntimeError('Screen or size changed while capturing this frame.')
    data=inspect(after)
    (args.out/'widgets.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    print(f"{data['screen']}: {len(data['clippedWidgets'])} clipped, {len(data['overlapCandidates'])} overlaps to inspect")


if __name__=='__main__':
    main()
