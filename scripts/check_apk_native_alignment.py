"""Inspect packaged ELF segments: require 16 KB on 64-bit, report 32-bit separately."""
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
            # Android's 16 KB runtime requirement applies to arm64/x86_64.
            # Inspect 32-bit libraries too, but do not label 4 KB prebuilts 16 KB-ready.
            required=16384 if is64 else 4096
            assert alignment>=required and file_offset%required==vaddr%required,(name,row)
            segments.append(alignment)
        assert segments,name
        libraries.append({'library':name,'elfBits':64 if is64 else 32,
            'requiredAlignment':16384 if is64 else 4096,'loadAlignments':segments})
assert libraries,'APK contains no native libraries'
result={'apk':args.apk.name,'pageSize':16384,'status':'PASS','libraries':libraries,
    'note':'64-bit ELF: 16 KB static alignment. 32-bit ELF: 4 KB check only, not labelled 16 KB-ready. Run zipalign -c -P 16 4 separately; actual 16 KB runtime remains required.'}
output=json.dumps(result,indent=2)+'\n'
if args.output:args.output.write_text(output,encoding='utf-8')
print(output)
