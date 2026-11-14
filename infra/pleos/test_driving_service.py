"""Independent negative tests: missing D/background evidence must never pass the integration oracle."""
import copy
import unittest
import struct
import zlib
from driving_service import verify_auto_continuity,verify_long_recording,png_pixel


class LongRecordingOracleTest(unittest.TestCase):
    def setUp(self):
        self.trace={'startMs':1000,'endMs':681000}
        for key in ('speed','steering','readings'):
            self.trace[key]=[{'atMs':662000+i*100,'available':True} for i in range(3)]
        self.checkpoints=[{'sessionId':'a','phase':'RECORDING','updatedMs':662000}]
    def errors(self):return verify_long_recording(self.trace,self.checkpoints,'a',660000)
    def test_complete_long_record_passes(self):self.assertEqual([],self.errors())
    def test_late_timestamps_alone_are_not_evidence(self):
        self.trace['speed']=[]
        self.assertTrue(self.errors())
    def test_early_stop_is_rejected(self):
        self.trace['endMs']=601000
        self.assertTrue(self.errors())
    def test_split_session_is_rejected(self):
        self.checkpoints[0]['sessionId']='b'
        self.assertTrue(self.errors())
    def test_premature_save_is_rejected(self):
        self.checkpoints[0]['phase']='SAVED'
        self.assertTrue(self.errors())
    def test_missing_live_checkpoint_is_rejected(self):
        self.checkpoints=[]
        self.assertTrue(self.errors())
    def test_stale_live_checkpoint_is_rejected(self):
        self.checkpoints[0]['updatedMs']=601000
        self.assertTrue(self.errors())
    def test_old_or_unavailable_evidence_is_rejected(self):
        self.trace['readings']=[{'atMs':1001,'available':True},{'atMs':662000,'available':False}]
        self.assertTrue(self.errors())


class VisibleSurfaceTest(unittest.TestCase):
    @staticmethod
    def png(color):
        def chunk(kind,body):return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(kind+body))
        return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(bytes([0,*color])))+chunk(b'IEND',b'')
    def test_visible_routy_surface_matches(self):self.assertEqual((242,245,241),png_pixel(self.png((242,245,241)),0,0))
    def test_gray_launcher_cover_is_not_a_pass(self):self.assertNotEqual((242,245,241),png_pixel(self.png((239,239,239)),0,0))
    def test_missing_screenshot_is_rejected(self):
        with self.assertRaises(ValueError):png_pixel(b'',0,0)


class AutomaticContinuityOracleTest(unittest.TestCase):
    def setUp(self):
        names=['drive-stationary','reverse','neutral','drive-moving','short-park','resumed-drive','background','returned-park']
        self.stages=[{'name':name,'sessionId':'session-a','automatic':True,'samples':10*(i+1),'updatedMs':(i+1)*4000,
                      'gear':{'reverse':2,'neutral':1,'short-park':4,'returned-park':4}.get(name,8)} for i,name in enumerate(names)]
        self.trace={'origin':'LIVE','truncated':False,'endMs':40_000,'analysis':{'result':'evidence'},'speed':[],'steering':[],'readings':[]}
        for row in self.stages:
            self.trace['speed'] += [{'atMs':row['updatedMs']-i*100,'available':True,'value':24/3.6} for i in range(16)]
            self.trace['steering'] += [{'atMs':row['updatedMs']-i*100,'available':True,'value':40 if i%2 else -40} for i in range(16)]
            if row['name'] in ('reverse','neutral'):self.trace['readings'].append({'signal':'AAOS.GEAR_SELECTION','available':True,'atMs':row['updatedMs']-500,'state':'REVERSE' if row['name']=='reverse' else 'NEUTRAL'})
        self.queue={'sessionId':'session-a','state':'PENDING','payload':{'report':copy.deepcopy(self.trace['analysis'])}}
    def errors(self):return verify_auto_continuity(self.stages,self.trace,'session-a',self.queue,30_000)
    def test_complete_evidence_passes(self):self.assertEqual([],self.errors())
    def test_reverse_must_not_split_session(self):
        self.stages[1]['sessionId']='another';self.assertTrue(self.errors())
    def test_neutral_must_not_split_session(self):
        self.stages[2]['sessionId']='another';self.assertTrue(self.errors())
    def test_stationary_neutral_does_not_require_a_new_speed_callback(self):
        self.stages[2]['samples']=self.stages[1]['samples'];self.assertEqual([],self.errors())
    def test_missing_raw_gear_evidence_fails(self):
        self.trace['readings']=[];self.assertTrue(self.errors())
    def test_delayed_poll_uses_original_gear_timestamp(self):
        for row in self.trace['readings']:
            row['providerTimestampMs']=row['atMs'];row['atMs']+=4000
        self.assertEqual([],self.errors())
    def test_stale_gear_from_before_the_transition_fails(self):
        for row in self.trace['readings']:row['providerTimestampMs']=0
        self.assertTrue(self.errors())
    def test_background_samples_must_advance(self):
        self.stages[6]['samples']=self.stages[5]['samples'];self.assertTrue(self.errors())
    def test_early_park_end_is_rejected(self):
        self.trace['endMs']=39_999;self.assertTrue(self.errors())
    def test_speed_mismatch_is_rejected(self):
        for r in self.trace['speed']:r['value']=0
        self.assertTrue(self.errors())
    def test_initial_zero_to_target_transition_is_preserved(self):
        end=self.stages[3]['updatedMs']
        self.trace['speed'] += [{'atMs':end-1900,'value':0,'available':True}]
        self.assertEqual([],self.errors())
    def test_return_to_zero_after_target_is_rejected(self):
        self.trace['speed'].append({'atMs':self.stages[3]['updatedMs']-50,'value':0,'available':True})
        self.assertTrue(self.errors())
    def test_single_target_sample_is_insufficient(self):
        end=self.stages[3]['updatedMs']
        self.trace['speed']=[r for r in self.trace['speed'] if not end-2000<=r['atMs']<=end]+[{'atMs':end,'value':24/3.6,'available':True}]
        self.assertTrue(self.errors())
    def test_background_speed_transition_is_rejected(self):
        self.trace['speed'].append({'atMs':self.stages[6]['updatedMs']-1900,'value':0,'available':True})
        self.assertTrue(self.errors())
    def test_steering_must_continue_in_background(self):
        self.trace['steering']=[];self.assertTrue(self.errors())
    def test_different_stored_report_is_rejected(self):
        self.queue['payload']['report']={};self.assertTrue(self.errors())
    def test_missing_transition_is_rejected(self):
        self.stages.pop();self.assertTrue(self.errors())


if __name__=='__main__':unittest.main()
