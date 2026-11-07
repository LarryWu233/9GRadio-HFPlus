"""Summarize a completed LiveReceptionTrace JSONL without altering the data."""
from pathlib import Path
import argparse
import json

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('trace',type=Path)
parser.add_argument('--output',type=Path)
args=parser.parse_args()
data=args.trace.read_bytes()
rows=[json.loads(line) for line in data.decode('utf-16' if data.startswith(b'\xff\xfe') else 'utf-8-sig').splitlines() if line.strip()]
configuration=rows[0]
samples=rows[1:]
assert configuration['kind']=='configuration' and len(samples)>1
first,last=samples[0],samples[-1]
def counter(row,key):return int(row['audio'].get(key,'0'))
result={
    'configuration':configuration,'windows':len(samples),'durationMs':last['elapsedMs'],
    'observedIqRate':sum(row['iqFrames'] for row in samples)*1000/last['elapsedMs'],
    'iqFlowDropsDelta':last['iqFlowDrops']-first['iqFlowDrops'],
    'observerSequenceGaps':last['observerSequenceGaps'],
    'pcmQueueDropsDelta':counter(last,'PCM_QUEUE_DROPS')-counter(first,'PCM_QUEUE_DROPS'),
    'playbackUnderrunsBeginEnd':[first['playbackUnderruns'],last['playbackUnderruns']],
    'writeFailuresBeginEnd':[counter(first,'AT_WRITE_FAIL'),counter(last,'AT_WRITE_FAIL')],
    'sqlClosedWindows':sum(not row['stats']['squelchOpen'] for row in samples),
    'maximumObservedCallbackGapUs':max(row['maxCallbackGapUs'] for row in samples),
    'underrunEvents':[{'elapsedMs':row['elapsedMs'],'count':row['playbackUnderruns']} for previous,row in zip(samples,samples[1:]) if row['playbackUnderruns']!=previous['playbackUnderruns']],
}
control_keys=['frequencyHz','reportedSampleRate','bandwidthHz','noiseReducer','noiseBlanker']
result['controlWindowsComplete']=all(all(key in row for key in control_keys) for row in samples)
if result['controlWindowsComplete']:
    result['controlStates']={key:sorted({row[key] for row in samples}) for key in control_keys}
    result['controlStates']['demodMode']=sorted({row['stats']['demodMode'] for row in samples})
    result['controlsUnchanged']=all(len(values)==1 for values in result['controlStates'].values())
result['zeroInputWindows']=sum(row['iqFrames']==0 for row in samples)
if any('blockedTouchEvents' in row for row in samples):
    result['blockedTouchEvents']=max(row.get('blockedTouchEvents',0) for row in samples)
clock_rows=[row for row in samples if row.get('playbackClock',{}).get('timestampNanos',-1)>0]
if len(clock_rows)>1:
    a,b=clock_rows[0],clock_rows[-1]
    ca,cb=a['playbackClock'],b['playbackClock']
    result['platformClockFramesPerSecond']=(cb['timestampFrames']-ca['timestampFrames'])*1e9/(cb['timestampNanos']-ca['timestampNanos'])
    result['writtenFramesPerSecond']=(cb['writtenFrames']-ca['writtenFrames'])*1000/(b['elapsedMs']-a['elapsedMs'])
    result['writeHeadDifferenceFirstLast']=[ca['writtenFrames']-ca['playbackHeadFrames'],cb['writtenFrames']-cb['playbackHeadFrames']]
    result['clockNote']='Write/head difference includes frames flushed before this trace; compare changes, not an absolute latency.'
output=json.dumps(result,ensure_ascii=False,indent=2)+'\n'
if args.output:args.output.write_text(output,encoding='utf-8')
print(output)
