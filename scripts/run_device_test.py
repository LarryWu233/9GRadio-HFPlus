"""Run this app's Android test with an explicit launcher intent for Xiaomi MIUI."""
import argparse
from pathlib import Path
import subprocess
import time
from android_tools import find_adb

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', help='ADB executable (otherwise PATH or Android SDK environment)')
parser.add_argument('--serial',required=True)
parser.add_argument('--package',default='com.radiosport.ninegradio.hfplus',help='Target application ID; use .arch for the architecture preview')
parser.add_argument('--class-name',required=True)
parser.add_argument('--output',required=True)
parser.add_argument('--extra',action='append',default=[],help='instrumentation key=value')
parser.add_argument('--headless',action='store_true',help='Do not launch the receiver UI; isolates non-UI benchmarks from automatic USB connection and display work')
parser.add_argument('--timeout',type=int,default=240,help='Whole instrumentation timeout in seconds; long RF runs need their duration plus startup/shutdown allowance')
args=parser.parse_args()
assert args.class_name.startswith('com.radiosport.ninegradio.')
assert 1<=args.timeout<=7200
destination=Path(args.output).resolve()
assert destination.is_relative_to(Path.cwd().resolve())
adb=[find_adb(args.adb),'-s',args.serial]
extra=[]
for item in args.extra:
    key,value=item.split('=',1)
    extra+=['-e',key,value]
command=adb+['shell','am','instrument','-w','-e','class',args.class_name]+extra+[
    args.package+'.test/androidx.test.runner.AndroidJUnitRunner']
with destination.open('wb') as log:
    process=subprocess.Popen(command,stdout=log,stderr=subprocess.STDOUT)
    if not args.headless:
        time.sleep(2)
        subprocess.run(adb+['shell','am','start','-W','-a','android.intent.action.MAIN',
            '-c','android.intent.category.LAUNCHER','-n',
            args.package+'/com.radiosport.ninegradio.ui.MainActivity'],check=True,capture_output=True)
    try:
        process.wait(timeout=args.timeout)
    except subprocess.TimeoutExpired:
        # A timed-out adb client does not stop Android instrumentation. Stop only
        # the explicitly selected application, so it cannot keep receiving or
        # competing with the next run after this runner has reported failure.
        subprocess.run(adb+['shell','am','force-stop',args.package],capture_output=True,timeout=15)
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        raise SystemExit('Android test timed out; selected application was stopped')
data=destination.read_text(encoding='utf-8',errors='replace')
print(data)
if 'OK (' not in data or 'FAILURES!!!' in data or 'INSTRUMENTATION_FAILED' in data:
    raise SystemExit('Android test did not finish successfully')
