import unittest
from screen_latency_report import summarize


class ReportTest(unittest.TestCase):
    def test_incomplete_screen_not_counted_as_fast_success(self):
        report = summarize('''I ScreenLatency: screen_latency id=1 event=T0 ms=100 cpu_ms=0 heap_bytes=1000
screen_latency id=1 event=regions count=2
screen_latency id=1 event=T6 ms=250
screen_latency id=1 event=drawn count=2
screen_latency id=1 event=translation_finished count=1
screen_latency id=1 event=T7 ms=400 cpu_ms=80 heap_bytes=2000
screen_latency id=2 event=T0 ms=500
screen_latency id=2 event=regions count=1
''')
        self.assertEqual(2, report['screens_started'])
        self.assertEqual(1, report['full_translation']['n'])
        self.assertEqual(300, report['full_translation']['p95_ms'])
        self.assertEqual(150, report['first_sentence']['median_ms'])
        self.assertEqual(.5, report['translation_failure_rate'])
        self.assertEqual(80, report['per_screen']['1']['max_sampled_cpu_ms'])

    def test_no_samples_returns_null_not_zero(self):
        report = summarize('unrelated log')
        self.assertIsNone(report['first_sentence'])
        self.assertIsNone(report['translation_failure_rate'])

    def test_nearest_rank_p95(self):
        text = '\n'.join(f'screen_latency id={i} event=T0 ms=0\n'
                         f'screen_latency id={i} event=T7 ms={i}' for i in range(1, 21))
        self.assertEqual(19, summarize(text)['full_translation']['p95_ms'])


if __name__ == '__main__':
    unittest.main()
