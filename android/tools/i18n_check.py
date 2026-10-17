#!/usr/bin/env python3
"""Every Hebrew text in the app's code must have a translation in each assets/i18n/<code>.json, with the same {n}
placeholders. Prints what is missing; exits 1 when anything is. Run: python3 android/tools/i18n_check.py"""
import json, os, re, subprocess, sys

here = os.path.dirname(os.path.abspath(__file__))
src = os.path.join(here, '..', 'app', 'src', 'main', 'java', 'com', 'blueshield', 'app')
i18n = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'i18n')
keys = json.loads(subprocess.check_output([sys.executable, os.path.join(here, 'i18n_keys.py'), src]))
ph = re.compile(r'\{\d+\}')
bad = 0
for f in sorted(os.listdir(i18n)):
    table = json.load(open(os.path.join(i18n, f), encoding='utf-8'))
    for k in keys:
        if k not in table:
            print(f'{f}: missing: {k}'); bad += 1
        elif sorted(ph.findall(k)) != sorted(ph.findall(table[k])):
            print(f'{f}: placeholders differ: {k} -> {table[k]}'); bad += 1
print(f'{len(keys)} texts, {len(os.listdir(i18n))} languages, {bad} problems')
sys.exit(1 if bad else 0)
