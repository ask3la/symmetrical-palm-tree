#!/usr/bin/env python3
"""Basic static validation for .mcrm ZIP plugin archives (CI preflight)."""
from pathlib import Path
import json, re, zipfile, sys
ID = re.compile(r'[a-z][a-z0-9_]{1,63}\Z')
ALLOWED = {'create','update','delete','emit','notify','calendar_upsert','set_field','schedule_reminder'}

def validate(path):
    with zipfile.ZipFile(path) as z:
        entries=z.infolist()
        assert 1<=len(entries)<=64, 'bad entry count'
        assert len({e.filename for e in entries})==len(entries), 'duplicate paths'
        for e in entries:
            assert e.filename=='manifest.json' or e.filename.startswith('assets/'), e.filename
            assert '..' not in e.filename and not e.filename.startswith('/'), 'unsafe path'
            assert 0<=e.file_size<=2_000_000, 'entry too large'
        assert sum(e.file_size for e in entries)<=4_000_000, 'archive too large'
        m=json.loads(z.read('manifest.json'))
    assert m['api']==2, 'bad API'
    assert ID.fullmatch(m['id']), 'bad plugin id'
    assert path.stem==m['id'], 'bad archive filename'
    local={c['id'] for c in m.get('collections',[])}
    assert len(local)==len(m.get('collections',[])), 'duplicate collections'
    for c in m.get('collections',[]):
        assert ID.fullmatch(c['id']), 'bad collection id'
        fields=c.get('fields',[])
        assert len({f['id'] for f in fields})==len(fields), 'duplicate fields'
    for p in m.get('pages',[]):
        if p.get('kind','collection') in ('collection','metrics'):
            assert p['collection'] in local, 'invalid page collection'
    for step in [s for group in m.get('actions',[])+m.get('hooks',[]) for s in group.get('steps',[])]:
        assert step['type'] in ALLOWED, step['type']
    return m['id'],len(m.get('extensions',[])),len(m.get('hooks',[]))

if __name__=='__main__':
    paths=[Path(p) for p in sys.argv[1:]] or list(Path('plugins').glob('*.mcrm'))
    for path in paths:
        name,extensions,hooks=validate(path)
        print(f'OK {name}: {extensions} extension(s), {hooks} hook(s)')
