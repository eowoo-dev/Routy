"""User-flow integration on the local Pleos emulator only: P -> D stationary -> D moving -> HOME -> P.
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


def verify_continuity(snapshots, trace, session_id, queued):
    errors=[]
    if [s['name'] for s in snapshots] != ['park','drive-stationary','drive-moving','background','returned-park']:
        errors.append('Missing P/D/background/P stages')
    for previous,current in zip(snapshots,snapshots[1:]):
        if current.get('sessionId')!=session_id or current.get('samples',0)<=previous.get('samples',0):
            errors.append(current['name']+': session changed or acquisition did not advance')
    for sample in snapshots[1:4]:
        if sample.get('gear')!=8 or sample.get('requiresOptimization') is not True or sample.get('uxKnown') is not True:
            errors.append(sample['name']+': D/UX restriction was not independently observed')
    if not snapshots or snapshots[-1].get('gear')!=4 or snapshots[-1].get('requiresOptimization') is not False:
        errors.append('Final P/unrestricted state was not observed')
    if trace.get('origin')!='LIVE' or trace.get('truncated') is not False:
        errors.append('Expected untruncated LIVE input')
    for sample in snapshots[1:4]:
        end=sample['updatedMs'];start=end-2000
        rows=[r for r in trace.get('speed',[]) if r.get('available') is True and start<=r.get('atMs',-1)<=end]
        expected=0 if sample['name']=='drive-stationary' else 24/3.6
        if len(rows)<5 or any(abs(r['value']-expected)>0.8 for r in rows):
            errors.append(sample['name']+': commanded speed absent from raw SDK evidence')
        if sample['name']=='background':
            angles=[r['value'] for r in trace.get('steering',[]) if r.get('available') is True and start<=r.get('atMs',-1)<=end]
            if len(angles)<2 or max(angles)-min(angles)<20:
                errors.append('background: steering callbacks did not continue')
    if queued.get('sessionId')!=session_id or queued.get('state')!='PENDING' or queued.get('payload',{}).get('report')!=trace.get('analysis'):
        errors.append('Offline queue does not preserve this exact evaluation')
    return errors


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial',default='emulator-5554')
    parser.add_argument('--console-url',default='http://127.0.0.1:8088')
    parser.add_argument('--adb')
    args=parser.parse_args()
    emu=Emulator(args)
    output=ROOT/'apps/pleos/driving-evaluation/build/scenarios/driving-service'
    output.mkdir(parents=True,exist_ok=True)
    client='routy-service-test-'+uuid.uuid4().hex
    stop=threading.Event();worker=None;heartbeat_errors=[];web_stopped=False;lease_started=False
    session_id=None;snapshots=[];errors=[];proof={}
    def app_json(path):
        raw=emu.shell('run-as',PACKAGE,'--user',emu.user,'cat',path,check=False)
        try:return json.loads(raw)
        except json.JSONDecodeError:return None
    def journal():return app_json('no_backup/active-trip.json') or {}
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
        for _ in range(7):
            emu.shell('uiautomator','dump','/sdcard/routy-service-window.xml')
            root=ET.fromstring(emu.shell('cat','/sdcard/routy-service-window.xml'))
            node=next((n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('text')==text and n.get('enabled')=='true'),None)
            if node is not None:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds','')))
                if x2>x1 and y2>y1:
                    emu.shell('input','tap',(x1+x2)//2,(y1+y2)//2);return
            scroll=next((n for n in root.iter('node') if n.get('package')==PACKAGE and n.get('scrollable')=='true'),None)
            if scroll is not None:
                x1,y1,x2,y2=map(int,re.findall(r'\d+',scroll.get('bounds')))
                emu.shell('input','swipe',(x1+x2)//2,y2-90,(x1+x2)//2,y1+90,250)
            time.sleep(.4)
        raise RuntimeError('User control unavailable: '+text)
    def home():emu.shell('am','start','-W','--user',emu.user,'-f','0x10008000','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-n',PACKAGE+'/com.noonmaru.routy.pleos.feature.trip.DrivingActivity')
    def stage(name,seconds=4):
        until=time.monotonic()+seconds
        step=0
        while time.monotonic()<until:
            if name in ('drive-moving','background'):
                emu.request('/vehicle/controls','PUT',speed=24,accelerator=0,brake=0,steering=[0,40,0,-40][step%4]);step+=1
            time.sleep(.5)
        row=journal();row['name']=name;snapshots.append(row)
        if row.get('sessionId')!=session_id or row.get('phase')!='RECORDING':raise RuntimeError(name+': recorder stopped or session changed')
        print('INPUT',name,'samples',row.get('samples'),flush=True)
    try:
        require_exclusive_console(lease)
        prior=journal()
        if prior.get('phase')=='RECORDING' and 'TripService' in emu.shell('dumpsys','activity','services',PACKAGE):
            raise RuntimeError('An existing practice is active; finish it before running this test')
        state=emu.request('/vehicle/state')
        if state['gear']['actual']['value']!='Park' or state['controls']['speed_kph']!=0:raise RuntimeError('Begin in Park at zero speed')
        worker=threading.Thread(target=heartbeat,daemon=True);worker.start()
        wait(lambda:emu.request('/vehicle/state')['gear']['bridge_ready'],'Console bridge unavailable')
        emu.reset();time.sleep(2)
        wait(healthy,'Development API unavailable',60)
        web_stopped=True;compose('stop')
        home()
        row=None
        for attempt in range(3):
            click_text('운전 연습 시작')
            try:
                row=wait(lambda:(r if (r:=journal()).get('phase')=='RECORDING' and r.get('sessionId')!=prior.get('sessionId') else None),'No fresh service capture',5)
                break
            except RuntimeError:
                # A connection-error banner can reflow the parked page between dump and tap.
                # Re-read its layout and retry the visible user control, never bypass startup checks.
                if attempt==2:raise
        session_id=row['sessionId']
        stage('park')
        emu.request('/vehicle/gear','PUT',gear='Drive')
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
        emu.request('/vehicle/controls','PUT',speed=24,accelerator=0,brake=0,steering=0)
        stage('drive-moving',5)
        # A stopped Activity is required evidence; simply obscuring the screen is insufficient.
        emu.shell('input','keyevent',3)
        time.sleep(1)
        dump=emu.shell('dumpsys','activity','activities')
        (output/'background-activities.txt').write_text(dump,encoding='utf-8')
        blocks=re.split(r'(?=\* Hist #)',dump)
        proof['activityStopped']=any('DrivingActivity' in b and re.search(r'(?:mState|state)=STOPPED',b) for b in blocks)
        if not proof['activityStopped']:raise RuntimeError('Driving Activity STOPPED state was not observed')
        stage('background',5)
        emu.reset();stage('returned-park')
        home();click_text('연습 마치고 저장')
        saved=wait(lambda:(r if (r:=journal()).get('sessionId')==session_id and r.get('phase')=='SAVED' else None),'Service did not finish durable storage',60)
        trace=app_json('files/sensor-traces/'+session_id+'.json')
        queued=app_json('no_backup/driving-records/'+session_id+'.json')
        if not trace or not queued:raise RuntimeError('Exact session trace or queue file missing')
        errors.extend(verify_continuity(snapshots,trace,session_id,queued))
        (output/'latest.json').write_text(json.dumps(trace,ensure_ascii=False,indent=2),encoding='utf-8')
        proof['offlineStored']=queued['state']=='PENDING'
        compose('start');web_stopped=False;wait(healthy,'API recovery failed',90)
        wait(lambda:(r if (r:=app_json('no_backup/driving-records/'+session_id+'.json')) and r['state']=='SENT' else None),'Stored record was not uploaded',180)
        credentials=app_json('no_backup/vehicle-installation.json')
        token=credentials['installationId']+'.'+credentials['secret']
        detail=api('/api/driving-records/'+session_id,token)
        proof['serverReportMatches']=detail['report']==trace['analysis']
        if not proof['serverReportMatches']:errors.append('Server lost or changed evaluation')
        page=api('/api/driving-records?limit=50',token)
        if sum(r['sessionId']==session_id for r in page['items'])!=1:errors.append('Duplicate or missing server record')
        proof['recordId']=detail['id']
        wait(lambda:'TripService' not in emu.shell('dumpsys','activity','services',PACKAGE),'Service did not release after saving')
        proof['serviceReleased']=True
    except (Exception,KeyboardInterrupt) as exc:errors.append(type(exc).__name__+': '+str(exc))
    finally:
        if worker:
            try:
                emu.reset();time.sleep(2)
                restored=emu.request('/vehicle/state')
                if restored['gear']['actual']['value']!='Park' or any(restored['controls'][k]!=0 for k in ('speed_kph','accelerator_pct','brake_pct','steering_deg')) or restored['lights']['turn_signal_mode']!='Off':
                    errors.append('Emulator restoration was not acknowledged')
            except Exception as exc:errors.append('Emulator restore: '+str(exc))
            if errors and session_id and journal().get('sessionId')==session_id and journal().get('phase')=='RECORDING':
                emu.shell('am','force-stop','--user',emu.user,PACKAGE)
            stop.set();worker.join(timeout=5)
        if lease_started:
            try:emu.request('/browser/session','POST',client=client,closing=1)
            except Exception as exc:errors.append('Console close: '+str(exc))
        if web_stopped:
            try:compose('start')
            except Exception as exc:errors.append('API restore: '+str(exc))
        errors.extend(heartbeat_errors[:3])
        result={'passed':not errors,'sessionId':session_id,'stages':snapshots,'proof':proof,'errors':errors}
        (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print('PASS' if not errors else 'FAIL',json.dumps(errors,ensure_ascii=False),flush=True)
    return 0 if not errors else 1

if __name__=='__main__':sys.exit(main())
