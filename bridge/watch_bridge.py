# -*- coding: utf-8 -*-
"""简易守护：桥接器退出后自动重启（可选用）。"""
import subprocess, sys, threading, time, os
HERE = os.path.dirname(os.path.abspath(__file__))

def run():
    while True:
        subprocess.call([sys.executable, os.path.join(HERE, 'dsh_phone_bridge.py')])
        print('bridge exited, restarting in 3s...')
        time.sleep(3)

if __name__ == '__main__':
    run()
