"""Offline checks for test fixture identity, nutrition, coverage and timezones."""

import importlib.util
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("seed", Path(__file__).with_name("seed-test0001.py"))
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)


class SeedTests(unittest.TestCase):
    now = seed.datetime(2026, 10, 28, 3, tzinfo=seed.timezone.utc)
    member = "e1a97056-efc0-424a-b4e3-65c20f4237ac"

    def test_month_has_ninety_consistent_meals_across_dst(self):
        start, days = seed.period(self.now.astimezone(seed.ZONE).date(), 30)
        profile, rows = seed.prepare(start, days, self.now, self.member, 1)
        self.assertEqual(start, seed.date(2026, 9, 28))
        self.assertEqual(len(rows), 90)
        self.assertEqual(len({record["recordId"] for record, _ in rows}), 90)
        dates = seed.Counter()
        offsets = set()
        for record, payload in rows:
            local = seed.instant(record["mealTime"]).astimezone(seed.ZONE)
            dates[local.date()] += 1
            offsets.add(local.strftime("%z"))
            self.assertEqual(record["memberId"], self.member)
            self.assertEqual(record["status"], "VALIDATED")
            self.assertEqual(record["nutrients"], payload["nutrients"])
            self.assertEqual(record["recordId"], payload["recordId"])
            self.assertEqual(record["mealTime"], payload["mealTime"])
            self.assertLess(seed.instant(record["mealTime"]), self.now)
        self.assertEqual(dates, seed.Counter({start + seed.timedelta(days=i): 3 for i in range(30)}))
        self.assertEqual(offsets, {"+1000", "+1100"})
        self.assertEqual(profile["memberId"], self.member)

    def test_overlapping_periods_reuse_ids_despite_new_profile_version(self):
        _, month = seed.prepare(seed.date(2026, 9, 28), 30, self.now, self.member, 1)
        _, week = seed.prepare(seed.date(2026, 10, 19), 7, self.now, self.member, 2)
        month_ids = {record["recordId"] for record, _ in month}
        self.assertTrue({record["recordId"] for record, _ in week}.issubset(month_ids))
        self.assertTrue(all(record["profileVersion"] == payload["profileVersion"] == 2
                            for record, payload in week))

    def test_accounts_receive_distinct_fixture_ids(self):
        _, first = seed.prepare(seed.date(2026, 10, 19), 7, self.now, self.member, 1)
        _, second = seed.prepare(seed.date(2026, 10, 19), 7, self.now, seed.MEMBER_ID, 1)
        self.assertTrue({r["recordId"] for r, _ in first}.isdisjoint({r["recordId"] for r, _ in second}))

    def test_default_week_preserves_previous_script_ids(self):
        start, days = seed.period(seed.date(2026, 10, 28))
        self.assertEqual((start, days), (seed.date(2026, 10, 19), 7))
        _, rows = seed.prepare(start, days, self.now, seed.MEMBER_ID, 1)
        expected = {str(seed.uuid5(seed.NAMESPACE_URL,
                    f"nutrimarket/test-fixtures/test0001/{start}/{r['recordId']}"))
                    for r in seed.source_meals()}
        self.assertEqual({record["recordId"] for record, _ in rows}, expected)

    def test_invalid_periods_are_rejected_before_database_access(self):
        today = seed.date(2026, 10, 28)
        for days in [0, -1, 367]:
            with self.assertRaises(ValueError):
                seed.period(today, days)
        with self.assertRaises(ValueError):
            seed.period(today, 7, start=today)
        with self.assertRaises(ValueError):
            seed.period(today, week_start=seed.date(2026, 10, 20))


if __name__ == "__main__":
    unittest.main()
