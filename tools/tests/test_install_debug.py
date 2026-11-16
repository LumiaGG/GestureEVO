import unittest

from tools.install_debug import SERVICE, online_devices, service_bound, service_list


class InstallDebugTest(unittest.TestCase):
    def test_ignores_offline_and_unauthorized_devices(self):
        self.assertEqual(["phone"], online_devices(
            "List of devices attached\nold offline\nphone device product:vivo\nother unauthorized\n"))

    def test_changes_only_debug_service(self):
        other = "other.package/.Service"
        shorthand = "io.github.lumiagg.gestureevo.debug/io.github.lumiagg.gestureevo.gesture.GestureService"
        self.assertEqual([other], service_list(f"{other}:{shorthand}", False))
        self.assertEqual([other, SERVICE], service_list(f"{other}:{shorthand}", True))
        self.assertEqual([SERVICE], service_list("null", True))

    def test_enabled_or_crashed_does_not_count_as_bound(self):
        self.assertFalse(service_bound(f"Enabled services:{{{SERVICE}}}\nCrashed services:{{{SERVICE}}}"))
        self.assertTrue(service_bound(f"Bound services:{{{SERVICE}}}"))
        self.assertTrue(service_bound("Bound services:{label=GestureEVO Debug, feedbackType=FEEDBACK_GENERIC}"))


if __name__ == "__main__":
    unittest.main()
