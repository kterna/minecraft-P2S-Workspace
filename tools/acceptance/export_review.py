"""Collect compact, shareable review evidence from one accept.py run. No config files or raw logs are copied."""
import argparse
from pathlib import Path
import json
import shutil
import zipfile


def export(run, output):
    run = run.resolve(strict=True)
    meta = json.loads((run/'acceptance.json').read_text())
    output.mkdir(mode=0o700,parents=True,exist_ok=False)
    status = meta.get('status','unknown')
    lines = ['# P2S 实机验收', '',
             f"版本 `{meta['version']}`，运行模式 `{meta['mode']}`，结果 **{status}**。", '',
             f"目标提交 `{meta['target']['commit']}`。"]
    if meta.get('jar'):
        lines += ['',f"测试 JAR 的 SHA-256 为 `{meta['jar']['sha256']}`。"]
    if meta.get('error'):
        lines += ['','运行反馈','',str(meta['error'])]
    def copy(name):
        src=run/name
        dest=output/name
        if src.is_file():
            dest.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(src,dest)
            return True
        return False
    copy('acceptance.json')
    if copy('panel.png'):
        lines+=['','[实际打开的面板](panel.png)']
    if copy('panel-layout.json'):
        geo=json.loads((output/'panel-layout.json').read_text())
        lines+=['',f"面板逻辑尺寸 {geo['guiWidth']} × {geo['guiHeight']}，越界控件 {len(geo['clippedWidgets'])} 个，待查看的重叠 {len(geo['overlapCandidates'])} 处。"]
    for suite in ('core','extended'):
        if not copy(suite+'/report.json'):
            continue
        result=json.loads((output/suite/'report.json').read_text())
        lines += ['', '## '+suite, '']
        for step in result['steps']:
            lines.append(f"- {'通过' if step['passed'] else '失败'} · {step['name']}" + (' · '+str(step['error']) if step.get('error') else ''))
        lines.append('')
        for name in result['screenshots']:
            if Path(name).name != name or Path(name).suffix.lower() != '.png':
                raise ValueError('Unexpected screenshot name')
            rel=suite+'/'+name
            if copy(rel): lines.append(f'[{name}]({rel})')
    # Keep a concise cause when a release JVM fails before an HTTP request can be issued.
    log=run/'client.log'
    if log.exists():
        causes=[]
        for line in log.open(errors='replace'):
            for prefix in ('java.lang.NoClassDefFoundError:', 'java.lang.ClassNotFoundException:'):
                if prefix in line:
                    typ=line.split(prefix,1)[1].strip().split(' ',1)[0]
                    if typ and all(c.isalnum() or c in '/._$' for c in typ):
                        causes.append(prefix+' '+typ)
        if causes:
            lines+=['','## 客户端类加载错误','']+['- `'+c+'`' for c in dict.fromkeys(causes)]
    (output/'README.md').write_text('\n'.join(lines)+'\n')
    archive=output.with_name(output.name+'.zip')
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for f in sorted(output.rglob('*')):
            if f.is_file():z.write(f, f.relative_to(output))
    return archive


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--run-dir',type=Path,required=True)
    p.add_argument('--out',type=Path,required=True)
    args=p.parse_args()
    print(export(args.run_dir,args.out))
