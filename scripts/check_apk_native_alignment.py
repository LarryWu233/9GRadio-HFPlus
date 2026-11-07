"""Check every packaged ELF PT_LOAD against Android's 16 KB page requirement."""
import argparse,json,struct,zipfile
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('apk',type=Path)
parser.add_argument('--output',type=Path)
args=parser.parse_args()
libraries=[]
with zipfile.ZipFile(args.apk) as apk:
    for name in apk.namelist():
        if not name.startswith('lib/') or not name.endswith('.so'):continue
        data=apk.read(name)
        assert data[:4]==b'\x7fELF' and data[5]==1,name
        is64=data[4]==2
        offset=struct.unpack_from('<Q' if is64 else '<I',data,32 if is64 else 28)[0]
        size,count=struct.unpack_from('<HH',data,54 if is64 else 42)
        segments=[]
        for index in range(count):
            row=struct.unpack_from('<IIQQQQQQ' if is64 else '<IIIIIIII',data,offset+index*size)
            if row[0]!=1:continue
            file_offset,vaddr,alignment=(row[2],row[3],row[7]) if is64 else (row[1],row[2],row[7])
            assert alignment>=16384 and file_offset%16384==vaddr%16384,(name,row)
            segments.append(alignment)
        assert segments,name
        libraries.append({'library':name,'loadAlignments':segments})
assert libraries,'APK contains no native libraries'
result={'apk':args.apk.name,'pageSize':16384,'status':'PASS','libraries':libraries,
    'note':'ELF validation only; run zipalign -c -P 16 4 separately; runtime on a 16 KB device is still required.'}
output=json.dumps(result,indent=2)+'\n'
if args.output:args.output.write_text(output,encoding='utf-8')
print(output)
