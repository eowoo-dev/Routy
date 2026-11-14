import copy
import unittest
from driving_service import verify_interrupted_record,moving_checkpoint

class RecoveryOracleTest(unittest.TestCase):
    def fixture(self):
        return {'sessionId':'trip','state':'SENT','receipt':{'sessionId':'trip'},'archive':{'parts':2,'nextPart':2},
                'payload':{'origin':'LIVE','report':{'startMs':100,'endMs':200,'recovery':{'status':'INTERRUPTED'},'feedback':[{'id':'interrupted-trip'}]}}}
    def test_live_interrupted_upload_with_receipt_is_accepted(self):
        self.assertEqual([],verify_interrupted_record(self.fixture(),'trip'))
    def test_normal_completion_wrong_receipt_and_partial_upload_are_rejected(self):
        paths=[('sessionId','other'),('state','PENDING'),('receipt',{'sessionId':'other'}),('archive',{'parts':2,'nextPart':1}),
               ('payload',{'origin':'SYNTHETIC','report':{'startMs':100,'endMs':200,'recovery':None,'feedback':[]}})]
        for key,value in paths:
            with self.subTest(key=key):
                row=copy.deepcopy(self.fixture());row[key]=value
                self.assertTrue(verify_interrupted_record(row,'trip'))

class MovingCheckpointTest(unittest.TestCase):
    def fixture(self):return {'sessionId':'trip','phase':'RECORDING','gear':8,'updatedMs':2000,'samples':20}
    def test_fresh_observed_drive_and_new_samples_are_required(self):
        self.assertTrue(moving_checkpoint(self.fixture(),'trip',2000,15))
    def test_stale_checkpoint_is_rejected(self):
        self.assertFalse(moving_checkpoint(self.fixture(),'trip',2001,15))
    def test_neutral_does_not_count_as_drive(self):
        row=self.fixture();row['gear']=1
        self.assertFalse(moving_checkpoint(row,'trip',2000,15))
    def test_unchanged_samples_are_rejected(self):
        self.assertFalse(moving_checkpoint(self.fixture(),'trip',2000,20))
    def test_other_session_is_rejected(self):
        self.assertFalse(moving_checkpoint(self.fixture(),'other',2000,15))
    def test_finished_capture_is_rejected(self):
        row=self.fixture();row['phase']='SAVED'
        self.assertFalse(moving_checkpoint(row,'trip',2000,15))

if __name__=='__main__':unittest.main()
