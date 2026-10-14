#!/usr/bin/env python3
"""Samples the phone while a game loads and plays, to show what a slow load or a freeze is waiting for.

Usage:
    tools/profile-run.py [-p PACKAGE] [-i SECONDS] [-o FILE.csv] [-d SECONDS]

Start it, launch the game, load a map and play. Press Enter every time you see a freeze (a mark is stored with the
time). Press Ctrl+C to stop: it prints a summary, with the numbers around your marks next to the overall ones.

What each column means (all taken from the phone, no root):
  cpu_user/sys/iow/idle  share of all CPU time (iow = waiting for storage)
  psi_cpu/io/mem         share of time that tasks were stalled waiting for CPU / storage / memory (Linux PSI)
  swap_mb, swapin/out    swap in use, pages swapped per second (zram: memory pressure)
  majflt                 major page faults per second (file data read on demand, e.g. from storage)
  mem_avail_mb           MemAvailable
  freq_min/max           lowest / highest current CPU frequency over the cores, MHz (low = throttled)
  temp_max               hottest thermal zone, C
  top                    busiest threads of the app (by name), %CPU of one core
"""
import argparse
import os
import re
import select
import statistics
import subprocess
import sys
import time

ADB = os.environ.get('ADB', '/opt/homebrew/share/android-commandlinetools/platform-tools/adb')
if not os.path.exists(ADB):
    ADB = 'adb'

REMOTE = r'''
echo "#STAT"; head -1 /proc/stat
echo "#PSI"; for f in cpu io memory; do echo "$f $(grep some /proc/pressure/$f | sed 's/.*total=//')"; done
echo "#MEM"; grep -E "MemAvailable|SwapTotal|SwapFree" /proc/meminfo
echo "#VM"; grep -E "^(pswpin|pswpout|pgmajfault) " /proc/vmstat
echo "#FREQ"; for c in /sys/devices/system/cpu/cpu[0-9]*; do cat $c/cpufreq/scaling_cur_freq 2>/dev/null; done
echo "#TEMP"; cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null
echo "#TOP"; top -H -b -n 1 -m 60 2>/dev/null
'''


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


def sections(text):
    out, cur = {}, None
    for line in text.splitlines():
        if line.startswith('#'):
            cur = line[1:].strip()
            out[cur] = []
        elif cur:
            out[cur].append(line)
    return out


def sample(uid):
    s = sections(adb('shell', REMOTE))
    r = {}
    f = s.get('STAT', [''])[0].split()
    r['stat'] = [int(x) for x in f[1:9]] if len(f) > 8 else [0] * 8
    r['psi'] = {k: int(v) for k, v in (l.split() for l in s.get('PSI', []) if len(l.split()) == 2)}
    mem = {}
    for l in s.get('MEM', []):
        m = re.match(r'(\w+):\s+(\d+)', l)
        if m:
            mem[m.group(1)] = int(m.group(2))
    r['mem'] = mem
    vm = {}
    for l in s.get('VM', []):
        k, v = l.split()
        vm[k] = int(v)
    r['vm'] = vm
    freqs = [int(x) // 1000 for x in s.get('FREQ', []) if x.strip().isdigit()]
    r['freq'] = freqs
    temps = [int(x) for x in s.get('TEMP', []) if x.strip().lstrip('-').isdigit()]
    temps = [t / 1000 if abs(t) > 1000 else t for t in temps]
    temps = [t for t in temps if 0 < t < 100]  # some zones report a placeholder
    r['temp'] = max(temps) if temps else 0
    threads = []
    for l in s.get('TOP', []):
        p = l.split()
        # TID USER PR NI VIRT RES SHR S[%CPU] %MEM TIME+ THREAD PROCESS
        if len(p) >= 12 and p[0].isdigit() and p[1] == uid:
            try:
                threads.append((float(p[8]), p[11]))
            except ValueError:
                pass
    threads.sort(reverse=True)
    r['top'] = threads[:6]
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('-p', default='com.winlator.cmod.dev2')
    ap.add_argument('-i', type=float, default=2.0)
    ap.add_argument('-o', default='profile-run.csv')
    ap.add_argument('-d', type=float, default=0, help='stop after this many seconds (default: Ctrl+C)')
    a = ap.parse_args()

    m = re.search(r'appId=(\d+)', adb('shell', f'dumpsys package {a.p}'))
    uid = f'u0_a{int(m.group(1)) - 10000}' if m else ''
    if not uid:
        sys.exit('The app is not installed, or no device over ADB.')
    print(f'Sampling every {a.i:g} s for {a.p} ({uid}). Launch the game; Enter = a freeze happened; Ctrl+C = stop.')

    rows, marks = [], []
    stdin_open = True
    prev = sample(uid)
    prev['_t'] = time.time()
    t0 = time.time()
    hdr = 'time cpu_user cpu_sys cpu_iow cpu_idle psi_cpu psi_io psi_mem swap_mb swapin swapout majflt mem_avail_mb freq_min freq_max temp_max top'
    print(hdr.replace('time', ' time'))
    try:
        while not a.d or time.time() - t0 < a.d:
            deadline = time.time() + a.i
            while time.time() < deadline:
                if stdin_open and select.select([sys.stdin], [], [], 0.2)[0]:
                    if not sys.stdin.readline():
                        stdin_open = False
                        continue
                    marks.append(time.time() - t0)
                    print(f'   ^ mark at {marks[-1]:.0f} s')
                elif not stdin_open:
                    time.sleep(0.2)
            cur = sample(uid)
            dt = time.time() - prev['_t'] if '_t' in prev else a.i
            cur['_t'] = time.time()
            ds = [c - p for c, p in zip(cur['stat'], prev['stat'])]
            tot = sum(ds) or 1
            # /proc/stat: user nice system idle iowait irq softirq steal
            row = {
                't': time.time() - t0,
                'cpu_user': 100 * (ds[0] + ds[1]) / tot,
                'cpu_sys': 100 * (ds[2] + ds[5] + ds[6]) / tot,
                'cpu_iow': 100 * ds[4] / tot,
                'cpu_idle': 100 * ds[3] / tot,
                'psi_cpu': 100 * (cur['psi'].get('cpu', 0) - prev['psi'].get('cpu', 0)) / (dt * 1e6),
                'psi_io': 100 * (cur['psi'].get('io', 0) - prev['psi'].get('io', 0)) / (dt * 1e6),
                'psi_mem': 100 * (cur['psi'].get('memory', 0) - prev['psi'].get('memory', 0)) / (dt * 1e6),
                'swap_mb': (cur['mem'].get('SwapTotal', 0) - cur['mem'].get('SwapFree', 0)) / 1024,
                'swapin': (cur['vm'].get('pswpin', 0) - prev['vm'].get('pswpin', 0)) / dt,
                'swapout': (cur['vm'].get('pswpout', 0) - prev['vm'].get('pswpout', 0)) / dt,
                'majflt': (cur['vm'].get('pgmajfault', 0) - prev['vm'].get('pgmajfault', 0)) / dt,
                'mem_avail_mb': cur['mem'].get('MemAvailable', 0) / 1024,
                'freq_min': min(cur['freq']) if cur['freq'] else 0,
                'freq_max': max(cur['freq']) if cur['freq'] else 0,
                'temp_max': cur['temp'],
                'top': ' '.join(f'{n}:{c:.0f}' for c, n in cur['top'][:4]),
            }
            rows.append(row)
            print(f"{row['t']:5.0f}  {row['cpu_user']:4.0f} {row['cpu_sys']:4.0f} {row['cpu_iow']:4.0f} {row['cpu_idle']:4.0f}  "
                  f"{row['psi_cpu']:4.0f} {row['psi_io']:4.0f} {row['psi_mem']:4.0f}  {row['swap_mb']:5.0f} {row['swapin']:5.0f} "
                  f"{row['swapout']:5.0f} {row['majflt']:5.0f} {row['mem_avail_mb']:5.0f}  {row['freq_min']:4.0f} {row['freq_max']:4.0f} "
                  f"{row['temp_max']:3.0f}  {row['top']}")
            prev = cur
    except KeyboardInterrupt:
        pass

    keys = [k for k in rows[0] if k != 'top'] if rows else []
    with open(a.o, 'w') as f:
        f.write(','.join(keys + ['top']) + '\n')
        for r in rows:
            f.write(','.join(f'{r[k]:.2f}' for k in keys) + ',"' + r['top'] + '"\n')
    print(f'\nSaved {len(rows)} samples to {a.o}')
    if not rows:
        return

    def avg(sel, k):
        v = [r[k] for r in sel]
        return statistics.mean(v) if v else float('nan')

    near = [r for r in rows if any(abs(r['t'] - m) <= a.i * 1.5 for m in marks)]
    print('\nSummary (overall | around your marks):')
    for k in ('cpu_user', 'cpu_sys', 'cpu_iow', 'psi_cpu', 'psi_io', 'psi_mem', 'swapin', 'swapout', 'majflt', 'freq_min', 'temp_max'):
        mx = max(r[k] for r in rows)
        print(f'  {k:9s} avg {avg(rows, k):7.1f}  max {mx:7.1f}' + (f'  | near marks avg {avg(near, k):7.1f}' if near else ''))
    names = {}
    for r in rows:
        for part in r['top'].split():
            n, _, c = part.rpartition(':')
            names[n] = max(names.get(n, 0), float(c or 0))
    print('  busiest threads (peak %CPU of one core):', ', '.join(f'{n} {c:.0f}' for n, c in sorted(names.items(), key=lambda x: -x[1])[:8]))


if __name__ == '__main__':
    main()
