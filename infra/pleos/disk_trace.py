"""Read bounded device windows for emulator assertions; production never combines them in RAM."""
import hashlib
import json
import uuid


def disk_trace(index, read_text):
    if index.get('storage')!='SEGMENTED':return index
    session=str(uuid.UUID(index['sessionId']))
    if index.get('rawDirectory')!=session:raise ValueError('Invalid raw archive directory')
    count=index['segments']
    if type(count) is not int or not 1<=count<=1_000_000:raise ValueError('Invalid window count')
    trace=dict(index)
    trace['contextFindings']=index.get('analysis',{}).get('contextFindings',[])
    fields=('speed','steering','readings','environment','speedGaps','steeringGaps')
    trace.update({key:[] for key in fields})
    root='files/sensor-traces/'+session
    end=index['startMs']
    for i in range(count):
        info=json.loads(read_text(f'{root}/window-{i}.json'))
        raw=read_text(f'{root}/raw-{i}.ndjson')
        if info['startMs']!=end or info['endMs']<=end:raise ValueError('Archive timeline gap')
        if len(raw.encode('utf-8'))>786432 or hashlib.sha256(raw.encode('utf-8')).hexdigest()!=info['sha256']:raise ValueError('Archive window damaged')
        end=info['endMs']
        for line in raw.splitlines():
            row=json.loads(line)
            if row['kind'] in fields:trace[row['kind']].append(row['value'])
    if end!=index['endMs']:raise ValueError('Archive ended early')
    return trace
