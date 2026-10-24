"""Tests for e2e.py (device choice, the Compose guard) and seed_feed.py. Run from this folder: python -I -m unittest"""

import os
import sys
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import e2e  # noqa: E402
import seed_feed  # noqa: E402

ADB_OUTPUT = "List of devices attached\nemulator-5554\tdevice\nRFGL41VWPFJ\tdevice\nR58M\tunauthorized\n\n"


class DeviceTest(unittest.TestCase):
    def test_only_ready_devices_are_listed(self):
        self.assertEqual(e2e.parse_devices(ADB_OUTPUT), ["emulator-5554", "RFGL41VWPFJ"])

    def test_two_devices_without_a_serial_are_refused(self):
        with self.assertRaises(e2e.Refused) as raised:
            e2e.choose_device(["emulator-5554", "RFGL41VWPFJ"], None)
        self.assertIn("ANDROID_SERIAL", str(raised.exception))

    def test_no_device_is_refused(self):
        with self.assertRaises(e2e.Refused):
            e2e.choose_device([], None)

    def test_a_serial_that_is_not_attached_is_refused(self):
        with self.assertRaises(e2e.Refused):
            e2e.choose_device(["emulator-5554"], "emulator-5556")

    def test_one_device_or_a_named_one_is_chosen(self):
        self.assertEqual(e2e.choose_device(["emulator-5554"], None), "emulator-5554")
        self.assertEqual(e2e.choose_device(["emulator-5554", "RFGL41VWPFJ"], "emulator-5554"), "emulator-5554")


class GuardTest(unittest.TestCase):
    def config(self, **changes):
        base = {
            "name": e2e.PROJECT,
            "services": {"server": {"image": e2e.IMAGE, "ports": [{"published": "18180"}]}},
            "volumes": {"podclatch-pgdata": {"name": e2e.PROJECT + "_podclatch-pgdata"}},
        }
        base.update(changes)
        return base

    def test_the_e2e_configuration_is_accepted(self):
        e2e.check_compose_config(self.config(), "18180")

    def test_another_project_image_port_or_volume_is_refused(self):
        wrong_image = {"server": {"image": "other:tag", "ports": [{"published": "18180"}]}}
        wrong_port = {"server": {"image": e2e.IMAGE, "ports": [{"published": "18180"}, {"published": "9"}]}}
        wrong_volume = {"podclatch-pgdata": {"name": "other_volume"}}
        for changes in ({"name": "other"}, {"services": wrong_image}, {"services": wrong_port}, {"volumes": wrong_volume}):
            with self.assertRaises(e2e.Refused):
                e2e.check_compose_config(self.config(**changes), "18180")

    def test_the_dev_port_is_refused(self):
        with self.assertRaises(e2e.Refused):
            e2e.check_compose_config(
                self.config(services={"server": {"image": e2e.IMAGE, "ports": [{"published": "8180"}]}}), "8180"
            )


class FeedTest(unittest.TestCase):
    def test_the_feed_has_three_episodes_with_descending_dates_and_loopback_audio(self):
        root = ET.fromstring(seed_feed.build_feed("http://127.0.0.1:8191/"))
        items = root.findall("./channel/item")
        self.assertEqual(len(items), 3)
        self.assertEqual(len({item.find("guid").text for item in items}), 3)
        for item in items:
            self.assertTrue(item.find("enclosure").get("url").startswith("http://127.0.0.1:8191/tone-"))
            self.assertTrue(item.find("{http://www.itunes.com/dtds/podcast-1.0.dtd}duration").text.isdigit())
        self.assertEqual(root.find("./channel/title").text, seed_feed.FEED_TITLE)


if __name__ == "__main__":
    unittest.main()
