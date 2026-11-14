import hashlib
import json
import unittest
from disk_trace import disk_trace


class DiskTraceTest(unittest.TestCase):
    def setUp(self):
        self.id='0ed1953c-47de-4b21-8c05-d2f61e885201'
        self.index={'storage':'SEGMENTED','sessionId':self.id,'rawDirectory':self.id,'segments':2,'startMs':0,'endMs':2000}
        self.files={}
        for i in range(2):
            root=f'files/sensor-traces/{self.id}'
            raw=json.dumps({'kind':'speed','value':{'atMs':i*1000+1,'available':True,'value':7}})+'\n'
            self.files[f'{root}/raw-{i}.ndjson']=raw
            self.files[f'{root}/window-{i}.json']=json.dumps({'startMs':i*1000,'endMs':(i+1)*1000,'sha256':hashlib.sha256(raw.encode()).hexdigest()})
    def test_all_windows_are_verified(self):self.assertEqual(2,len(disk_trace(self.index,self.files.__getitem__)['speed']))
    def test_evaluation_context_is_preserved_with_segmented_raw_inputs(self):
        self.index['analysis']={'contextFindings':[{'context':'BRAKING_MANEUVER'}]}
        self.assertEqual(self.index['analysis']['contextFindings'],disk_trace(self.index,self.files.__getitem__)['contextFindings'])
    def test_corruption_is_rejected(self):
        self.files[f'files/sensor-traces/{self.id}/raw-1.ndjson']+='damage'
        with self.assertRaises(ValueError):disk_trace(self.index,self.files.__getitem__)
    def test_missing_window_is_not_accepted(self):
        del self.files[f'files/sensor-traces/{self.id}/raw-1.ndjson']
        with self.assertRaises(KeyError):disk_trace(self.index,self.files.__getitem__)
    def test_unfinished_or_invalid_archive_is_rejected(self):
        self.index['endMs']=3000
        with self.assertRaises(ValueError):disk_trace(self.index,self.files.__getitem__)
        self.index['rawDirectory']='../other'
        with self.assertRaises(ValueError):disk_trace(self.index,self.files.__getitem__)
