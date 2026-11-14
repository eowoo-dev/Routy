import http.client
import io
import unittest
import urllib.error
from unittest.mock import patch
from emulator_scenarios import Emulator

class ConsoleTransportTest(unittest.TestCase):
    def emulator(self):
        value=object.__new__(Emulator);value.base='http://127.0.0.1:8088';return value
    def test_absolute_gear_selection_retries_identically_after_a_lost_response(self):
        with patch('urllib.request.urlopen',side_effect=[http.client.RemoteDisconnected(),io.BytesIO(b'{"ok":true}')]) as call,patch('time.sleep'):
            self.assertEqual({'ok':True},self.emulator().request('/vehicle/gear','PUT',gear='Drive'))
            self.assertEqual(2,call.call_count)
            self.assertEqual(call.call_args_list[0].args[0].full_url,call.call_args_list[1].args[0].full_url)
    def test_stable_client_lease_can_be_retried(self):
        with patch('urllib.request.urlopen',side_effect=[http.client.RemoteDisconnected(),io.BytesIO(b'{}')]) as call,patch('time.sleep'):
            self.emulator().request('/browser/session','POST',client='stable')
            self.assertEqual(2,call.call_count)
    def test_non_idempotent_scenario_and_anonymous_session_requests_are_not_retried(self):
        for path,method in [('/routy/scenarios/control','POST'),('/browser/session','GET')]:
            with self.subTest(path=path),patch('urllib.request.urlopen',side_effect=http.client.RemoteDisconnected()) as call:
                with self.assertRaises(http.client.RemoteDisconnected):self.emulator().request(path,method)
                self.assertEqual(1,call.call_count)
    def test_http_errors_are_not_retried(self):
        error=urllib.error.HTTPError('http://localhost',409,'conflict',{},io.BytesIO())
        self.addCleanup(error.close)
        with patch('urllib.request.urlopen',side_effect=error) as call:
            with self.assertRaises(urllib.error.HTTPError):self.emulator().request('/vehicle/state')
            self.assertEqual(1,call.call_count)
    def test_retries_are_bounded(self):
        with patch('urllib.request.urlopen',side_effect=http.client.RemoteDisconnected()) as call,patch('time.sleep'):
            with self.assertRaises(http.client.RemoteDisconnected):self.emulator().request('/vehicle/controls','PUT',speed=0)
            self.assertEqual(3,call.call_count)

if __name__=='__main__':unittest.main()
