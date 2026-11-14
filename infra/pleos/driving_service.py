"""Automatic recording integration on the local Pleos emulator: D -> R -> N -> D -> short P -> D -> HOME -> P (10s).
Temporarily stops only compose.dev.yaml web to verify durable offline saving, then restores it.
No UX restriction overrides, private VHAL injections, installation secret output, or physical cars.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import struct
import zlib
from emulator_scenarios import Emulator, PACKAGE, ROOT, require_exclusive_console, require_sole_console_writer
from disk_trace import disk_trace


def png_pixel(data,x,y):
    """Read an Android RGB/RGBA PNG pixel using stdlib, independent of the hidden UI tree."""
    if data[:8]!=b'\x89PNG\r\n\x1a\n':raise ValueError('Invalid screenshot')
    offset=8;compressed=[];size=None
    while offset<len(data):
        length=struct.unpack('>I',data[offset:offset+4])[0];kind=data[offset+4:offset+8];body=data[offset+8:offset+8+length];offset+=12+length
        if kind==b'IHDR':size=struct.unpack('>IIBBBBB',body)
        if kind==b'IDAT':compressed.append(body)
    if not size or size[2]!=8 or size[3] not in (2,6) or size[6]!=0:raise ValueError('Unsupported screenshot encoding')
    width,height=size[:2];channels=3 if size[3]==2 else 4
    if not 0<=x<width or not 0<=y<height or width*height>16_000_000:raise ValueError('Screenshot bounds')
    raw=zlib.decompress(b''.join(compressed));stride=width*channels;previous=bytearray(stride)
    for row in range(y+1):
        start=row*(stride+1);mode=raw[start];current=bytearray(raw[start+1:start+1+stride])
        if mode==2:current=bytearray((a+b)&255 for a,b in zip(current,previous))
        elif mode in (1,3,4):
            for i in range(stride):
                a=current[i-channels] if i>=channels else 0;b=previous[i];c=previous[i-channels] if i>=channels else 0
                if mode==1:predictor=a
                elif mode==3:predictor=(a+b)//2
                else:
                    p=a+b-c;pa,pb,pc=abs(p-a),abs(p-b),abs(p-c)
                    predictor=a if pa<=pb and pa<=pc else b if pb<=pc else c
                current[i]=(current[i]+predictor)&255
        elif mode!=0:raise ValueError('Invalid PNG filter')
        previous=current
    return tuple(previous[x*channels:x*channels+3])


def verify_auto_continuity(snapshots,trace,session_id,queued,park_since):
    errors=[]
    names=['drive-stationary','reverse','neutral','drive-moving','short-park','resumed-drive','background','returned-park']
    if [s['name'] for s in snapshots]!=names:errors.append('Missing automatic-recording stages')
    for i,row in enumerate(snapshots):
        if row.get('sessionId')!=session_id or not row.get('automatic'):errors.append(row['name']+': session changed or not automatic')
        if i and row.get('samples',0)<snapshots[i-1].get('samples',0):errors.append(row['name']+': sample counter reset')
        gear={'reverse':2,'neutral':1,'short-park':4,'returned-park':4}.get(row['name'],8)
        if row.get('gear')!=gear:errors.append(row['name']+': wrong observed gear')
        if row['name'] in ('reverse','neutral'):
            expected='REVERSE' if row['name']=='reverse' else 'NEUTRAL'
            # AAOS snapshots can arrive later than the gear callback. Correlate the
            # preserved provider timestamp, not a delayed polling receipt, to this phase.
            lower=snapshots[i-1]['updatedMs'] if i else trace.get('startMs',0)
            gear_rows=[r for r in trace.get('readings',[]) if r.get('signal')=='AAOS.GEAR_SELECTION' and r.get('available') and lower<=r.get('providerTimestampMs',r['atMs'])<=row['updatedMs']]
            if not any(r.get('state')==expected for r in gear_rows):errors.append(row['name']+': raw gear evidence missing')
        if row['name'] in ('drive-moving','background'):
            if i and row.get('samples',0)<=snapshots[i-1].get('samples',0):errors.append(row['name']+': speed acquisition stopped')
            end=row['updatedMs'];start=end-2000
            speed=[r for r in trace.get('speed',[]) if r.get('available') and start<=r['atMs']<=end]
            speed.sort(key=lambda r:r['atMs'])
            # The first acceleration may settle inside this window. Preserve its
            # actual zero-speed samples, then require a sustained expected tail.
            # Background driving must remain steady throughout the window.
            if row['name']=='drive-moving':
                settled=next((i for i,r in enumerate(speed) if abs(r['value']-24/3.6)<=.8),len(speed))
                if any(abs(r['value'])>.8 for r in speed[:settled]):errors.append('drive-moving: unexpected speed before settling')
                speed=speed[settled:]
            if len(speed)<5 or speed[-1]['atMs']-speed[0]['atMs']<500 or any(abs(r['value']-24/3.6)>.8 for r in speed):errors.append(row['name']+': raw SDK speed missing')
            if row['name']=='background':
                angles=[r['value'] for r in trace.get('steering',[]) if r.get('available') and start<=r['atMs']<=end]
                if len(angles)<2 or max(angles)-min(angles)<20:errors.append('background: steering stopped')
    if trace.get('origin')!='LIVE' or trace.get('truncated') is not False:errors.append('Expected untruncated LIVE acquisition')
    if trace.get('endMs',0)-park_since<10_000:errors.append('Record ended before ten continuous seconds of Park')
    if queued.get('sessionId')!=session_id or queued.get('state')!='PENDING' or queued.get('payload',{}).get('report')!=trace.get('analysis'):errors.append('Offline saved report differs from exact captured session')
    return errors


def verify_long_recording(trace, checkpoints, session_id, minimum_ms):
    """Require raw data past the former deadline, not just a long report timestamp."""
    errors=[]
    start=trace.get('startMs',0);end=trace.get('endMs',0)
    if end-start<minimum_ms:errors.append('Long recording ended too soon')
    if not checkpoints or checkpoints[-1].get('updatedMs',0)-start<minimum_ms:
        errors.append('No live checkpoint beyond the requested duration')
    for row in checkpoints:
        if row.get('sessionId')!=session_id or row.get('phase')!='RECORDING':errors.append('Long recording stopped or changed session')
    for field in ('speed','steering','readings'):
        rows=[r for r in trace.get(field,[]) if r.get('available') and start+minimum_ms<=r.get('atMs',0)<=end]
        if len(rows)<2:errors.append('Long recording: late '+field+' evidence missing')
    return errors


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--console-url',default='http://127.0.0.1:8088')
    parser.add_argument('--adb')
    parser.add_argument('--long-seconds',type=int,default=0,help='Hold a real background drive for at least this many seconds (minimum 660)')
    args=parser.parse_args()
    if args.long_seconds and args.long_seconds<660:parser.error('--long-seconds must be at least 660')
    emu=Emulator(args)
    output=ROOT/'apps/pleos/driving-evaluation/build/scenarios/driving-service'
    output.mkdir(parents=True,exist_ok=True)
    client='routy-service-test-'+uuid.uuid4().hex
    stop=threading.Event();worker=None;heartbeat_errors=[];web_stopped=False;lease_started=False
    session_id=None;snapshots=[];errors=[];proof={};owned=set();initial_enabled=False;settings_touched=False;long_checkpoints=[]
    def app_json(path):
        raw=emu.shell('run-as',PACKAGE,'--user',emu.user,'cat',path,check=False)
        try:return json.loads(raw)
        except json.JSONDecodeError:return None
    def journal():return app_json('no_backup/active-trip.json') or {}
    def preference():
        text=emu.shell('run-as',PACKAGE,'--user',emu.user,'cat','shared_prefs/driving-preferences.xml',check=False)
        try:return any(n.get('name')=='auto-record' and n.get('value')=='true' for n in ET.fromstring(text))
        except ET.ParseError:return False
    def uptime():return int(float(emu.shell('cat','/proc/uptime').split()[0])*1000)
    def wait(action,message,timeout=30):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            value=action()
            if value:return value
            time.sleep(.4)
        raise RuntimeError(message)
    def lease():
        nonlocal lease_started
        lease_started=True
        return emu.request('/browser/session','POST',client=client)
    def heartbeat():
        while not stop.is_set():
            try:require_sole_console_writer(lease)
            except Exception as exc:heartbeat_errors.append(str(exc))
            stop.wait(.6)
    def compose(action):
        subprocess.run(['docker','compose','-f','compose.dev.yaml',action,'web'],cwd=ROOT,check=True,capture_output=True,timeout=60)
    def api(path,token=None):
        req=urllib.request.Request('http://127.0.0.1:3000'+path,headers={'Authorization':'Bearer '+token} if token else {})
        with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)
    def healthy():
        try:return api('/api/health').get('status')=='ok'
        except OSError:return False
    def click_text(text):
        names=(text,) if isinstance(text,str) else text
        for _ in range(7):
            emu.shell('uiautomator','dump','/sdcard/routy-service-window.xml')
            root=ET.fromstring(emu.shell('cat','/sdcard/routy-service-window.xml'))
            if not any(n.get('package')==PACKAGE for n in root.iter('node')):
                # Pleos has two resumed panes. A cold boot can leave accessibility focus on
                # the left vehicle pane although Routy's Activity is visible on the right.
                # Focus the observed, non-interactive Routy header on this fixed 2560x1440 image.
                emu.shell('input','tap',1700,160)
                continue
            node=next((n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('text') in names and n.get('enabled')=='true'),None)
            if node is not None:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
                if x2>x1 and y2>y1:
                    emu.shell('input','tap',(x1+x2)//2,(y1+y2)//2);return
            scroll=next((n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('scrollable')=='true'),None)
            if scroll is not None:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll.get('bounds')))
                emu.shell('input','swipe',(x1+x2)//2,y2-90,(x1+x2)//2,y1+90,250)
            time.sleep(.4)
        raise RuntimeError('User control unavailable: '+str(names))
    def home():emu.shell('am','start','-W','--user',emu.user,'-f','0x10008000','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/com.noonmaru.routy.pleos.feature.trip.DrivingActivity')
    def stage(name,seconds=4):
        if heartbeat_errors:raise RuntimeError(heartbeat_errors[0])
        until=time.monotonic()+seconds
        step=0
        while time.monotonic()<until:
            if heartbeat_errors:raise RuntimeError(heartbeat_errors[0])
            if name in ('drive-moving','background'):
                emu.request('/vehicle/controls','PUT',speed=24,accelerator=0,brake=0,steering=[0,40,0,-40][step%4]);step+=1
            time.sleep(.5)
        row=journal();row['name']=name;snapshots.append(row)
        if row.get('sessionId')!=session_id or row.get('phase') not in ('RECORDING','PARK_WAIT'):raise RuntimeError(name+': recorder stopped or session changed')
        print('INPUT',name,'samples',row.get('samples'),flush=True)
    try:
        require_exclusive_console(lease)
        prior=journal()
        if prior.get('phase') in ('RECORDING','PARK_WAIT','SAVING') and 'TripService' in emu.shell('dumpsys','activity','services',PACKAGE):
            raise RuntimeError('An existing practice is active; finish it before running this test')
        state=emu.request('/vehicle/state')
        if state['gear']['actual']['value']!='Park' or state['controls']['speed_kph']!=0:raise RuntimeError('Begin in Park at zero speed')
        worker=threading.Thread(target=heartbeat,daemon=True);worker.start()
        wait(lambda:emu.request('/vehicle/state')['gear']['bridge_ready'],'Console bridge unavailable')
        emu.reset();time.sleep(2)
        wait(healthy,'Development API unavailable',60)
        initial_enabled=preference()
        settings_touched=True
        home()
        if not initial_enabled:click_text('자동 기록 켜기')
        wait(lambda:journal().get('armed') and journal().get('autoEnabled'),'Automatic recording was not armed')
        assert journal().get('phase') in ('ARMED','SAVED')
        floor=uptime()
        emu.shell('am','force-stop','--user',emu.user,PACKAGE)
        home()
        wait(lambda:journal().get('armed') and journal().get('updatedMs',0)>=floor,'Enabled setting did not re-arm on app launch')
        proof['enabledSettingPersisted']=preference()
        web_stopped=True;compose('stop')
        emu.request('/vehicle/gear','PUT',gear='Drive')
        row=wait(lambda:(r if (r:=journal()).get('phase')=='RECORDING' and r.get('sessionId')!=prior.get('sessionId') else None),'D did not start a fresh automatic capture')
        session_id=row['sessionId'];owned.add(session_id)
        stage('drive-stationary')
        emu.shell('uiautomator','dump','/sdcard/routy-service-window.xml')
        restricted_xml=emu.shell('cat','/sdcard/routy-service-window.xml')
        (output/'restricted-window.xml').write_text(restricted_xml,encoding='utf-8')
        texts=[n.get('text','') for n in ET.fromstring(restricted_xml).iter('node') if n.get('package')==PACKAGE]
        proof['restrictedUi']=('운전 연습 기록 중' in texts and not any(t in texts for t in ('연습 마치고 저장','계정 연결','개발 도구','연결 코드 발급')))
        if not proof['restrictedUi']:raise RuntimeError('Restricted screen exposed parked controls or was blocked')
        screenshot=subprocess.run([emu.adb,'-s',args.serial,'exec-out','screencap','-p'],capture_output=True,check=True,timeout=15)
        (output/'drive-restricted.png').write_bytes(screenshot.stdout)
        bounds=list(map(int,re.findall(r'\d+',ET.fromstring(restricted_xml).find('node').get('bounds'))))
        color=json.loads((ROOT/'packages/contracts/design/routy.tokens.json').read_text(encoding='utf-8'))['colors']['background'].lstrip('#')
        expected=tuple(int(color[i:i+2],16) for i in (0,2,4))
        proof['visibleDrivingSurface']=png_pixel(screenshot.stdout,bounds[0]+50,bounds[1]+50)==expected
        if not proof['visibleDrivingSurface']:raise RuntimeError('Launcher covers the driving Activity despite its accessible UI tree')
        emu.request('/vehicle/gear','PUT',gear='Reverse')
        wait(lambda:journal().get('gear')==2,'Reverse was not observed by the app');stage('reverse',3)
        emu.request('/vehicle/gear','PUT',gear='Neutral')
        wait(lambda:journal().get('gear')==1,'Neutral was not observed by the app');stage('neutral',3)
        emu.request('/vehicle/gear','PUT',gear='Drive')
        emu.request('/vehicle/controls','PUT',speed=24,accelerator=0,brake=0,steering=0)
        stage('drive-moving',5)
        emu.reset();stage('short-park',3)
        if app_json('no_backup/driving-records/'+session_id+'.json'):raise RuntimeError('Short Park ended the recording')
        emu.request('/vehicle/gear','PUT',gear='Drive');stage('resumed-drive',2)
        if journal().get('parkSinceMs') is not None:raise RuntimeError('D did not cancel the Park countdown')
        emu.request('/vehicle/controls','PUT',speed=24,accelerator=0,brake=0,steering=0)
        # A stopped Activity is required evidence; simply obscuring the screen is insufficient.
        emu.shell('input','keyevent',3)
        time.sleep(1)
        dump=emu.shell('dumpsys','activity','activities')
        (output/'background-activities.txt').write_text(dump,encoding='utf-8')
        blocks=re.split(r'(?=\* Hist #)',dump)
        proof['activityStopped']=any('DrivingActivity' in b and re.search(r'(?:mState|state)=STOPPED',b) for b in blocks)
        if not proof['activityStopped']:raise RuntimeError('Driving Activity STOPPED state was not observed')
        if args.long_seconds:
            # Real wall-clock soak, no clock overrides. Keep steady inputs during the
            # middle, then exercise steering again after the old ten-minute deadline.
            until=time.monotonic()+args.long_seconds;next_checkpoint=0
            emu.request('/vehicle/controls','PUT',speed=24,accelerator=0,brake=0,steering=0)
            while time.monotonic()<until:
                if heartbeat_errors:raise RuntimeError(heartbeat_errors[0])
                if time.monotonic()>=next_checkpoint:
                    r=journal();long_checkpoints.append(r)
                    if r.get('sessionId')!=session_id or r.get('phase')!='RECORDING':raise RuntimeError('Long drive stopped before Park')
                    if app_json('no_backup/driving-records/'+session_id+'.json'):raise RuntimeError('Long drive saved before Park')
                    print('SOAK',r.get('updatedMs',0)-r.get('startedMs',0),'ms samples',r.get('samples'),flush=True)
                    next_checkpoint=time.monotonic()+30
                time.sleep(1)
            long_checkpoints.append(journal())
        stage('background',5)
        emu.reset();stage('returned-park')
        park_since=journal()['parkSinceMs']
        if app_json('no_backup/driving-records/'+session_id+'.json'):raise RuntimeError('Record saved before Park hold completed')
        saved=wait(lambda:(r if (r:=journal()).get('sessionId')==session_id and r.get('phase')=='SAVED' else None),'Service did not finish durable storage',60)
        index=app_json('files/sensor-traces/'+session_id+'.json')
        trace=disk_trace(index,lambda path:emu.shell('run-as',PACKAGE,'--user',emu.user,'cat',path)) if index else None
        queued=app_json('no_backup/driving-records/'+session_id+'.json')
        if not trace or not queued:raise RuntimeError('Exact session trace or queue file missing')
        errors.extend(verify_auto_continuity(snapshots,trace,session_id,queued,park_since))
        if args.long_seconds:
            errors.extend(verify_long_recording(trace,long_checkpoints,session_id,args.long_seconds*1000))
            proof['longDurationMs']=trace['endMs']-trace['startMs']
        (output/'latest.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2),encoding='utf-8')
        proof['offlineStored']=queued['state']=='PENDING'
        compose('start');web_stopped=False;wait(healthy,'API recovery failed',90)
        def uploaded():
            row=app_json('no_backup/driving-records/'+session_id+'.json')
            if row and row['state']=='FAILED':raise RuntimeError('Stored record needs retry: '+str(row.get('error')))
            return row if row and row['state']=='SENT' else None
        wait(uploaded,'Stored record was not uploaded',180)
        credentials=app_json('no_backup/vehicle-installation.json')
        token=credentials['installationId']+'.'+credentials['secret']
        detail=api('/api/driving-records/'+session_id,token)
        proof['serverReportMatches']=detail['report']==trace['analysis']
        if not proof['serverReportMatches']:errors.append('Server lost or changed evaluation')
        if index.get('storage')=='SEGMENTED':
            import hashlib
            request=urllib.request.Request('http://127.0.0.1:3000/api/driving-records/'+session_id+'/archive',headers={'Authorization':'Bearer '+token})
            digest=hashlib.sha256();size=0
            with urllib.request.urlopen(request,timeout=30) as response:
                while block:=response.read(65536):digest.update(block);size+=len(block)
            local=app_json('no_backup/driving-records/'+session_id+'.json')['archive']
            proof['archiveMatches']=digest.hexdigest()==local['sha256'] and size==local['bytes'] and local['nextPart']==local['parts']
            proof['diskWindows']=index['segments']
            if not proof['archiveMatches']:errors.append('Full detailed archive differs or resume progress incomplete')
        page=api('/api/driving-records?limit=50',token)
        if sum(r['sessionId']==session_id for r in page['items'])!=1:errors.append('Duplicate or missing server record')
        proof['recordId']=detail['id']
        # A second D transition must create exactly one new session without pressing Start.
        emu.request('/vehicle/gear','PUT',gear='Drive')
        second=wait(lambda:(r if (r:=journal()).get('phase')=='RECORDING' and r.get('sessionId')!=session_id else None),'Second drive did not start')
        second_id=second['sessionId'];owned.add(second_id)
        time.sleep(2);emu.reset();time.sleep(2)
        home();click_text(('자동 기록 설정','사용자 홈으로'));click_text('자동 기록 끄기')
        wait(lambda:journal().get('sessionId')==second_id and journal().get('phase')=='SAVED' and not journal().get('armed'),'Turning automatic recording off did not save and stop')
        wait(lambda:'TripService' not in emu.shell('dumpsys','activity','services',PACKAGE),'Service did not release after disabling')
        floor=uptime()
        emu.shell('am','force-stop','--user',emu.user,PACKAGE);home()
        if preference():raise RuntimeError('Disabled preference did not persist')
        emu.request('/vehicle/gear','PUT',gear='Drive');time.sleep(4)
        if journal().get('sessionId')!=second_id or 'TripService' in emu.shell('dumpsys','activity','services',PACKAGE):raise RuntimeError('Disabled automatic recording started in D')
        proof['disabledDoesNotStart']=True;proof['disabledSettingPersisted']=True
        proof['secondSessionId']=second_id;proof['serviceReleased']=True
    except (Exception,KeyboardInterrupt) as exc:errors.append(type(exc).__name__+': '+str(exc))
    finally:
        if worker:
            try:
                emu.reset();time.sleep(2)
                restored=emu.request('/vehicle/state')
                if restored['gear']['actual']['value']!='Park' or any(restored['controls'][k]!=0 for k in ('speed_kph','accelerator_pct','brake_pct','steering_deg')) or restored['lights']['turn_signal_mode']!='Off':
                    errors.append('Emulator restoration was not acknowledged')
            except Exception as exc:errors.append('Emulator restore: '+str(exc))
            if errors and journal().get('sessionId') in owned and journal().get('phase') in ('RECORDING','PARK_WAIT','SAVING'):
                emu.shell('am','force-stop','--user',emu.user,PACKAGE)
            stop.set();worker.join(timeout=5)
        if lease_started:
            try:emu.request('/browser/session','POST',client=client,closing=1)
            except Exception as exc:errors.append('Console close: '+str(exc))
        if web_stopped:
            try:compose('start')
            except Exception as exc:errors.append('API restore: '+str(exc))
        if settings_touched:
            try:
                home()
                if preference()!=initial_enabled:
                    if journal().get('phase') in ('RECORDING','PARK_WAIT','SAVING'):click_text(('자동 기록 설정','사용자 홈으로'))
                    click_text('자동 기록 켜기' if initial_enabled else '자동 기록 끄기')
            except Exception as exc:errors.append('Preference restore: '+str(exc))
        errors.extend(heartbeat_errors[:3])
        result={'passed':not errors,'sessionId':session_id,'stages':snapshots,'longCheckpoints':long_checkpoints,'proof':proof,'errors':errors}
        (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS' if not errors else 'FAIL',json.dumps(errors,ensure_ascii=False),flush=True)
    return 0 if not errors else 1

if __name__=='__main__':sys.exit(main())
