"""Tests for the fishing layers build: python -I tools/fishing_layers/test_build_fishing_layers.py"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_fishing_layers as b  # noqa: E402


def rec(src, name, lon, lat, y=None, a="p3"):
    return {"src": src, "n": name, "pos": (lon, lat), "y": y, "a": a, "t": None, "l": None}


class NamesAndNumbers(unittest.TestCase):
    def test_names_match_without_prefixes_case_or_punctuation(self):
        self.assertEqual("swan", b.norm_name("HMAS Swan"))
        self.assertEqual("swan", b.norm_name("Swan (HMAS)"))
        self.assertEqual("lyeemoon", b.norm_name("Ly-ee-Moon"))
        self.assertEqual(b.norm_name("Glenmorry"), b.norm_name("Glen Morry"))
        self.assertEqual(b.norm_name("Unidentified Dredge No. 1 (possibly the Fremantle)"),
                         b.norm_name("Unidentified Dredge No. 1 (possibly the Fremantle"))
        self.assertEqual("encounter", b.norm_name("Encounter ex HMAS"))
        self.assertEqual("", b.norm_name(None))

    def test_decimals_count_only_what_was_given(self):
        self.assertEqual(0, b.decimals(140))
        self.assertEqual(0, b.decimals(140.0))
        self.assertEqual(1, b.decimals(151.6))
        self.assertEqual(3, b.decimals(151.797))

    def test_years_from_loss_dates(self):
        self.assertEqual(1942, b.year_of("1942/03/03"))
        self.assertEqual(1848, b.year_of("6/1848"))
        self.assertEqual(1848, b.year_of("11/08/1848"))
        self.assertEqual(1911, b.year_of(1911))
        self.assertIsNone(b.year_of("31821"))
        self.assertIsNone(b.year_of("unknown"))
        self.assertIsNone(b.year_of(None))

    def test_years_agree_allows_a_national_decade(self):
        self.assertTrue(b.years_agree(1901, 1902))
        self.assertTrue(b.years_agree(1901, 1910))
        self.assertFalse(b.years_agree(1901, 1911))
        self.assertFalse(b.years_agree(1909, 1990))
        self.assertTrue(b.years_agree(None, 1990))

    def test_degrees_and_decimal_minutes(self):
        self.assertAlmostEqual(23 + 6.841 / 60, b.dms("23", "6.841'"))
        self.assertAlmostEqual(19 + 10.965 / 60, b.dms("19", "  10.965'"))
        self.assertIsNone(b.dms("19", "61"))
        self.assertIsNone(b.dms(None, "1"))


class WreckMerging(unittest.TestCase):
    def test_a_state_record_replaces_the_national_one_and_keeps_its_year(self):
        national = [rec("A", "Gudrun", 113.5, -25.9, y=1901, a="p2")]
        wa = [rec("W", "Gudrun", 113.52, -25.91, a="g")]
        out = b.merge_wrecks(national, [wa])
        self.assertEqual(1, len(out))
        self.assertEqual("W", out[0]["src"])
        self.assertEqual(1901, out[0]["y"])
        self.assertEqual("A", out[0]["also"])

    def test_same_name_far_apart_or_years_apart_stay_two_wrecks(self):
        national = [rec("A", "Ellen", 144.9, -37.8, y=1850), rec("A", "Ellen", 138.5, -35.0, y=1880)]
        sa = [rec("S", "Ellen", 138.5, -35.0, y=1880, a="f"), rec("S", "Ellen", 138.6, -35.05, y=1960, a="nf")]
        out = b.merge_wrecks(national, [sa])
        self.assertEqual(3, len(out))
        self.assertEqual(1, sum(1 for r in out if r["src"] == "A"))

    def test_the_very_same_spot_is_one_wreck_whatever_the_years(self):
        national = [rec("A", "Dianella", 137.6, -34.0, y=1990)]
        sa = [rec("S", "Dianella", 137.6001, -34.0001, y=1909, a="f")]
        self.assertEqual(1, len(b.merge_wrecks(national, [sa])))

    def test_a_rough_national_position_matches_further_away(self):
        national = [rec("A", "Admella", 140.4, -38.0, y=1859, a="p1")]
        sa = [rec("S", "Admella", 140.62, -38.06, y=1859, a="f")]
        self.assertEqual(1, len(b.merge_wrecks(national, [sa])))
        national = [rec("A", "Admella", 140.4, -38.0, y=1859, a="p3")]
        self.assertEqual(2, len(b.merge_wrecks(national, [[dict(sa[0])]])))

    def test_a_second_national_copy_of_a_matched_wreck_goes(self):
        national = [rec("A", "Laura", 138.0, -34.5, y=1950, a="p2"), rec("A", "Laura", 138.02, -34.5, y=1950, a="p2")]
        sa = [rec("S", "Laura", 138.01, -34.5, y=1950, a="nf")]
        out = b.merge_wrecks(national, [sa])
        self.assertEqual(["S"], [r["src"] for r in out])

    def test_national_repeats_at_one_spot_are_kept_once(self):
        national = [rec("A", "Tweed", 153.55, -28.17, y=1849), rec("A", "Tweed", 153.5501, -28.1701, y=1849)]
        self.assertEqual(1, len(b.merge_wrecks(national, [])))

    def test_unnamed_wrecks_match_only_at_the_same_spot(self):
        national = [rec("A", None, 135.0, -32.0)]
        sa = [rec("S", None, 135.1, -32.0, y=1961, a="nf")]
        self.assertEqual(2, len(b.merge_wrecks(national, [sa])))
        sa = [rec("S", None, 135.0005, -32.0, y=1961, a="nf")]
        self.assertEqual(1, len(b.merge_wrecks([rec("A", None, 135.0, -32.0)], [sa])))


class SharkGear(unittest.TestCase):
    def test_drumlines_and_nets_from_the_spreadsheet_rows(self):
        rows = [
            ["Area", "Gold Coast"],
            ["Location", "Net/Drum", "Gear Number", "Degrees (South)", "Decimal Minutes ", "Degrees (East)", "Decimal Minutes "],
            ["Sheraton Mirage", "Drum Line", "1", "27", "57.687", "153", "26.159"],
            [None, "Drum Line", "2", "27", "57.804", "153", "26.14"],
            ["Main Beach", "Net", "net 1", "27", "58.434", "153", "26.153", "27", "58.528", "153", "26.155"],
            [None, None, None, None],
        ]
        feats = b.shark_gear({"Summary": [["Location"]], "Gold Coast": rows})
        self.assertEqual(["drum", "drum", "net"], [f["properties"]["k"] for f in feats])
        self.assertEqual("Sheraton Mirage", feats[1]["properties"]["b"])
        self.assertEqual("Drumline 2", feats[1]["properties"]["n"])
        self.assertEqual("Shark net 1", feats[2]["properties"]["n"])
        self.assertEqual("LineString", feats[2]["geometry"]["type"])
        lon, lat = feats[0]["geometry"]["coordinates"]
        self.assertAlmostEqual(153 + 26.159 / 60, lon, places=4)
        self.assertAlmostEqual(-(27 + 57.687 / 60), lat, places=4)


class Closures(unittest.TestCase):
    def test_place_names_from_schedule_entries(self):
        self.assertEqual("Wallabi Group", b.clean_wa_place("Schedule 1 - Reef Observation Areas (2. Wallabi Group)"))
        self.assertEqual("Harvey River", b.clean_wa_place("Item 1 - Area 3 - Harvey River"))
        self.assertEqual("Hamelin Bay", b.clean_wa_place("15 . Hamelin Bay"))
        self.assertEqual("Warnbro Sound (Safety Bay)", b.clean_wa_place("Schedule (1 - Warnbro Sound (Safety Bay) FD 54/45)"))
        self.assertEqual("Gairdner River", b.clean_wa_place("Item 10 - (3) Gairdner River"))
        self.assertEqual("North Island", b.clean_wa_place("North Island - All the waters within 600 metres of the jetty"))
        self.assertEqual("", b.clean_wa_place("Item 5 - Area 1"))
        self.assertEqual("", b.clean_wa_place("Clause 2 - Interpretation"))
        self.assertEqual("", b.clean_wa_place("All waters within 500m of any mangrove stand"))

    def test_one_area_under_two_orders_is_one_feature_with_both_rules(self):
        square = {"type": "Polygon", "coordinates": [[[115, -32], [115.1, -32], [115.1, -32.1], [115, -32.1], [115, -32]]]}
        a = {"geometry": square, "properties": {"n": "Serpentine River", "k": "limits", "w": "Limits on fishing for prawns.", "o": "Prawn notice"}}
        c = {"geometry": dict(square), "properties": {"n": "Serpentine River", "k": "closed", "w": "Limits on recreational netting.", "o": "Netting notice"}}
        out = b.merge_same_areas([a, c])
        self.assertEqual(1, len(out))
        self.assertEqual("Limits on fishing for prawns. Limits on recreational netting.", out[0]["properties"]["w"])
        self.assertEqual("Prawn notice; Netting notice", out[0]["properties"]["o"])
        self.assertEqual("closed", out[0]["properties"]["k"])

    def test_outlines_are_simplified_by_how_far_they_spread(self):
        small = {"type": "Polygon", "coordinates": [[[115, -32], [115.005, -32], [115.005, -32.005], [115, -32]]]}
        big = {"type": "Polygon", "coordinates": [[[115, -32], [117, -32], [117, -34], [115, -32]]]}
        self.assertLess(b.tolerance_for(small), b.tolerance_for(big))


if __name__ == "__main__":
    unittest.main()
