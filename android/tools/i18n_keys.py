#!/usr/bin/env python3
"""Lists every Hebrew text written in the app code (the keys of the translation tables) as JSON."""
import re,os,json,sys
heb=re.compile(r'[֐-׿]')
lit=re.compile(r'"((?:[^"\\]|\\.)*)"')
keys=[]
root=sys.argv[1]
for dp,_,fs in sorted(os.walk(root)):
    for f in sorted(fs):
        if not f.endswith('.kt'): continue
        s=open(os.path.join(dp,f)).read()
        for line in s.split('\n'):
            t=line.strip()
            if t.startswith('//') or t.startswith('*') or 'Breadcrumbs.mark' in t: continue
            for m in lit.finditer(line):
                b=m.group(1)
                if not heb.search(b) or '$' in b: continue
                k=json.loads('"'+b+'"')
                if k=='עברית': continue
                if k not in keys: keys.append(k)
print(json.dumps(keys,ensure_ascii=False,indent=0))
