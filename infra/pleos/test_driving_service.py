"""Independent negative tests: missing D/background evidence must never pass the integration oracle."""
import copy
import unittest
import struct
import zlib
from driving_service import verify_continuity,png_pixel


class VisibleSurfaceTest(unittest.TestCase):
    @staticmethod
    def png(color):
        def chunk(kind,body):return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(kind+body))
        return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(bytes([0,*color])))+chunk(b'IEND',b'')
    def test_visible_routy_surface_matches(self):self.assertEqual((242,245,241),png_pixel(self.png((242,245,241)),0,0))
    def test_gray_launcher_cover_is_not_a_pass(self):self.assertNotEqual((242,245,241),png_pixel(self.png((239,239,239)),0,0))
    def test_missing_screenshot_is_rejected(self):
        with self.assertRaises(ValueError):png_pixel(b'',0,0)


class ContinuityOracleTest(unittest.TestCase):
    def setUp(self):
        self.stages=[{'name':name,'sessionId':'session-a','samples':10*(i+1),'updatedMs':(i+1)*4000,
                      'gear':4 if i in (0,4) else 8,'uxKnown':True,'requiresOptimization':i not in (0,4)}
                     for i,name in enumerate(['park','drive-stationary','drive-moving','background','returned-park'])]
        self.trace={'origin':'LIVE','truncated':False,'analysis':{'result':'evidence'},'speed':[],'steering':[]}
        for s in self.stages[1:4]:
            self.trace['speed'] += [{'atMs':s['updatedMs']-i*100,'available':True,'value':0 if s['name']=='drive-stationary' else 24/3.6} for i in range(16)]
            self.trace['steering'] += [{'atMs':s['updatedMs']-i*100,'available':True,'value':40 if i%2 else -40} for i in range(16)]
        self.queue={'sessionId':'session-a','state':'PENDING','payload':{'report':copy.deepcopy(self.trace['analysis'])}}

    def errors(self):return verify_continuity(self.stages,self.trace,'session-a',self.queue)
    def test_complete_evidence_passes(self):self.assertEqual([],self.errors())
    def test_stationary_d_is_not_assumed_to_be_park(self):
        self.stages[1]['requiresOptimization']=False
        self.assertTrue(self.errors())
    def test_samples_must_advance_in_background(self):
        self.stages[3]['samples']=self.stages[2]['samples']
        self.assertTrue(self.errors())
    def test_restart_as_new_session_fails(self):
        self.stages[3]['sessionId']='session-b'
        self.assertTrue(self.errors())
    def test_old_or_missing_speed_evidence_fails(self):
        self.trace['speed']=self.trace['speed'][:16]
        self.assertTrue(self.errors())
    def test_commanded_speed_mismatch_fails(self):
        for r in self.trace['speed']:r['value']=0
        self.assertTrue(self.errors())
    def test_mismatched_stored_report_fails(self):
        self.queue['payload']['report']={}
        self.assertTrue(self.errors())
    def test_missing_background_steering_fails(self):
        self.trace['steering']=[]
        self.assertTrue(self.errors())
    def test_missing_park_return_fails(self):
        self.stages.pop()
        self.assertTrue(self.errors())


if __name__=='__main__':unittest.main()
