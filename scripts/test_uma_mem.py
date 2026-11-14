"""Tests for the text parsing and report logic in `uma_mem.py`. Run with `python scripts/test_uma_mem.py`."""

import datetime as dt
import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
import uma_mem  # noqa: E402

MEMINFO = """Applications Memory Usage (in Kilobytes):
Uptime: 11499509 Realtime: 11499509

** MEMINFO in pid 2002 [com.steve1316.uma_android_automation] **
                   Pss  Private  Private  SwapPss      Rss
         Native Heap   327240   327168       12      278   327736
 App Summary
           Java Heap:    34948                          46568
         Native Heap:   327240                         327736
                Code:    69976                         140100
               Stack:     2676                           2680
            Graphics:        7                              0
       Private Other:   110128
              System:    13295
             Unknown:                                  113056

           TOTAL PSS:   558263            TOTAL RSS:   630140       TOTAL SWAP PSS:      278
"""

EXIT_INFO = """ACTIVITY MANAGER PROCESS EXIT INFO (dumpsys activity exit-info)
  package: com.steve1316.uma_android_automation
    Historical Process Exit for uid=10065
        ApplicationExitInfo #0:
          timestamp=2026-11-05 20:11:03.731
          pid=517
          reason=4 (APP CRASH(EXCEPTION))
          subreason=0 (UNKNOWN)
        ApplicationExitInfo #1:
          timestamp=2026-11-05 19:30:01.457
          pid=2284
          reason=3 (LOW_MEMORY)
          subreason=4 (TRIM EMPTY)
"""

BITMAP_STACK = [
    ("_ZN7android6Bitmap18allocateHeapBitmapEmRK11SkImageInfom", "/system/lib64/libhwui.so"),
    ("android.graphics.Bitmap.createBitmap", "/system/framework/x86_64/boot-framework.oat"),
    ("com.steve1316.automation_library.utils.ImageUtils.getSourceBitmap", "/memfd:jit-cache (deleted)"),
    ("com.steve1316.uma_android_automation.utils.CustomImageUtils.getSourceBitmap", "/memfd:jit-cache (deleted)"),
    ("nterp_helper", "/apex/com.android.art/lib64/libart.so"),
    ("com.steve1316.uma_android_automation.bot.Game.loadingReason", "/memfd:jit-cache (deleted)"),
    ("com.steve1316.uma_android_automation.bot.Game.waitForLoading", "/memfd:jit-cache (deleted)"),
]


def at(clock: str) -> dt.datetime:
    """Build a device time on the test day.

    Args:
        clock: The time as `HH:MM:SS`.

    Returns:
        The datetime.
    """
    return dt.datetime.fromisoformat(f"2026-11-05 {clock}")


class ParseMeminfo(unittest.TestCase):
    def test_reads_a_process_that_has_never_swapped(self) -> None:
        # Android words this total differently until the process has swapped something out.
        text = """ App Summary
         Native Heap:   204364                         205160
           TOTAL PSS:   393863            TOTAL RSS:   523620      TOTAL SWAP (KB):        0
"""
        memory = uma_mem.parse_meminfo(text)
        self.assertIsNotNone(memory)
        self.assertEqual(memory["pss_kb"], 393863)
        self.assertEqual(memory["swap_kb"], 0)

    def test_reads_the_app_summary_not_the_detail_table(self) -> None:
        got = uma_mem.parse_meminfo(MEMINFO)
        self.assertEqual(got["native_kb"], 327240)
        self.assertEqual(got["java_kb"], 34948)
        self.assertEqual(got["graphics_kb"], 7)
        self.assertEqual(got["pss_kb"], 558263)
        self.assertEqual(got["rss_kb"], 630140)
        self.assertEqual(got["swap_kb"], 278)

    def test_returns_none_when_the_process_is_missing(self) -> None:
        self.assertIsNone(uma_mem.parse_meminfo("No process found for: com.steve1316.uma_android_automation\n"))


class ParseExitInfo(unittest.TestCase):
    def test_finds_the_record_for_the_pid(self) -> None:
        got = uma_mem.parse_exit_info(EXIT_INFO, 2284)
        self.assertEqual(got["reason"], "LOW_MEMORY")
        self.assertEqual(got["subreason"], "TRIM EMPTY")
        self.assertEqual(got["timestamp"], "2026-11-05 19:30:01.457")

    def test_returns_none_for_an_unknown_pid(self) -> None:
        self.assertIsNone(uma_mem.parse_exit_info(EXIT_INFO, 999))


class KeepLogLine(unittest.TestCase):
    def test_keeps_bot_and_kill_lines(self) -> None:
        self.assertTrue(uma_mem.keep_log_line("11-05 20:45:05.076  2002  2486 I [UAA]Companion: [INFO] Bot is not at the End screen"))
        self.assertTrue(uma_mem.keep_log_line("11-05 19:30:01.003  1295  4796 I am_proc_died: [0,2284,com.steve1316.uma_android_automation,100,4]"))
        self.assertTrue(uma_mem.keep_log_line("11-05 20:11:03.625 32575 32575 I perfetto: pprofd_producer.cc:1223 517 disconnected from heapprofd (ds shutting down: 1)."))

    def test_drops_selinux_noise_that_names_the_profiler(self) -> None:
        self.assertFalse(uma_mem.keep_log_line('11-05 20:18:10.919 32575 32575 I heapprofdunwind: type=1400 audit(0.0:10924): avc: denied { use } for path="/proc/2002/mem"'))
        self.assertFalse(uma_mem.keep_log_line("11-05 20:56:33.856  1043  1043 I lmkd    : type=1400 audit(0.0:11264): avc: denied { write }"))


class ParseLogLine(unittest.TestCase):
    def test_reads_time_with_the_given_year(self) -> None:
        self.assertEqual(uma_mem.log_time("11-05 20:45:05.076  2002  2486 I [UAA]Companion: x", 2026), dt.datetime(2026, 11, 5, 20, 45, 5, 76000))

    def test_rejects_a_line_without_a_time(self) -> None:
        self.assertIsNone(uma_mem.log_time("--------- beginning of main", 2026))

    def test_reads_the_mem_label(self) -> None:
        self.assertEqual(uma_mem.mem_label("11-05 20:45:05.076  2002  2486 I [UAA]MemoryLog: [MEM] day=12 pss=812MB native=550MB"), "day=12")
        self.assertEqual(uma_mem.mem_label("11-05 20:45:05.076  2002  2486 I [UAA]MemoryLog: [MEM] run_end pss=812MB"), "run_end")
        self.assertIsNone(uma_mem.mem_label("11-05 20:45:05.076  2002  2486 I [UAA]Game: [INFO] pss=812MB"))


class Topics(unittest.TestCase):
    def test_counts_bot_sections_and_skips_generic_levels(self) -> None:
        lines = [
            "11-05 20:45:05.076  2002  2486 I [UAA]Companion: [SKILLS] Reading a row",
            "11-05 20:45:05.076  2002  2486 I [UAA]Companion: [SKILLS] Reading a row",
            "11-05 20:45:05.076  2002  2486 I [UAA]Companion: [RACE] Starting",
            "11-05 20:45:05.076  2002  2486 I [UAA]Companion: [INFO] Nothing",
            "11-05 20:45:05.076  2002  2486 D [AUTO_LIB]MyAccessibilityService: Tapping",
        ]
        self.assertEqual(uma_mem.topics(lines), "SKILLS x2, RACE x1")

    def test_says_when_the_window_has_none(self) -> None:
        self.assertEqual(uma_mem.topics([]), "no bot log")


class Attribute(unittest.TestCase):
    def test_names_the_library_and_the_two_nearest_app_frames(self) -> None:
        self.assertEqual(uma_mem.attribute(BITMAP_STACK), "libhwui.so: CustomImageUtils.getSourceBitmap <- Game.loadingReason")

    def test_falls_back_to_the_library_frame_when_no_app_frame_exists(self) -> None:
        stack = [("malloc", "/apex/com.android.runtime/lib64/bionic/libc.so"), ("tesseract::Init", "/data/app/x/lib/x86_64/libtesseract.so")]
        self.assertEqual(uma_mem.attribute(stack), "libtesseract.so: tesseract::Init")

    def test_labels_a_failed_unwind(self) -> None:
        stack = [("malloc", "/apex/com.android.runtime/lib64/bionic/libc.so"), ("ERROR 2", "/ERROR")]
        self.assertEqual(uma_mem.attribute(stack), "unwind failed")

    def test_handles_a_stack_of_only_libc(self) -> None:
        self.assertEqual(uma_mem.attribute([("malloc", "/apex/com.android.runtime/lib64/bionic/libc.so")]), "libc.so: malloc")


class Growth(unittest.TestCase):
    def test_sees_a_step_up_as_growth(self) -> None:
        self.assertEqual(uma_mem.growth([10, 10, 10, 50, 50, 50]), 40)

    def test_sees_a_sawtooth_as_flat(self) -> None:
        self.assertEqual(uma_mem.growth([10, 90, 10, 90, 10, 90]), 0)

    def test_needs_at_least_three_snapshots(self) -> None:
        self.assertEqual(uma_mem.growth([10, 500]), 0)


class Jumps(unittest.TestCase):
    def test_ranks_the_biggest_rises_only(self) -> None:
        samples = [{"time": at("20:00:00"), "pss_kb": 100}, {"time": at("20:00:30"), "pss_kb": 500}, {"time": at("20:01:00"), "pss_kb": 200}, {"time": at("20:01:30"), "pss_kb": 300}]
        got = uma_mem.biggest_jumps(samples, 2)
        self.assertEqual([j["delta_kb"] for j in got], [400, 100])
        self.assertEqual(got[0]["start"], at("20:00:00"))
        self.assertEqual(got[0]["end"], at("20:00:30"))


class Warnings(unittest.TestCase):
    def test_flags_a_client_that_left_while_the_session_was_live(self) -> None:
        got = uma_mem.profiler_warnings(["11-05 20:11:03.625 32575 32575 I perfetto: pprofd_producer.cc:1223 517 disconnected from heapprofd (ds shutting down: 0)."])
        self.assertEqual(len(got), 1)
        self.assertIn("20:11:03", got[0])

    def test_ignores_the_normal_goodbye_at_the_end(self) -> None:
        self.assertEqual(uma_mem.profiler_warnings(["11-05 20:11:03.625 32575 32575 I perfetto: pprofd_producer.cc:1223 517 disconnected from heapprofd (ds shutting down: 1)."]), [])

    def test_flags_the_startup_stack_error(self) -> None:
        got = uma_mem.profiler_warnings(["11-05 20:06:10.000   517   517 E perfetto: client.cc:373 Failed to find stackend."])
        self.assertEqual(len(got), 1)


class Render(unittest.TestCase):
    def test_report_puts_the_answer_first(self) -> None:
        run = {
            "started": at("20:00:00"),
            "ended": "App died: LOW_MEMORY (TRIM EMPTY) at 2026-11-05 20:01:30.000",
            "samples": [
                {"time": at("20:00:00"), "pss_kb": 512000, "native_kb": 256000, "java_kb": 30000, "graphics_kb": 0, "swap_kb": 0, "avail_kb": 2048000},
                {"time": at("20:01:00"), "pss_kb": 1536000, "native_kb": 1280000, "java_kb": 30000, "graphics_kb": 0, "swap_kb": 0, "avail_kb": 1024000},
            ],
            "log_lines": [
                "11-05 20:00:10.000  2002  2486 I [UAA]MemoryLog: [MEM] day=5 pss=500MB",
                "11-05 20:00:20.000  2002  2486 I [UAA]Companion: [SKILLS] Reading a row",
            ],
            "native": {
                "times": [at("20:00:00"), at("20:00:30"), at("20:01:00")],
                "live_mb": [100.0, 200.0, 900.0],
                "groups": [{"name": "libhwui.so: Game.loadingReason", "series_mb": [50.0, 100.0, 700.0]}],
            },
        }
        text = uma_mem.render_report(run)
        self.assertLess(text.index("App died: LOW_MEMORY"), text.index("Timeline"))
        self.assertIn("first 500MB, peak 1500MB at 20:01:00", text)
        self.assertIn("day=5", text)
        self.assertIn("SKILLS x1", text)
        self.assertIn("libhwui.so: Game.loadingReason", text)
        self.assertIn("+650", text)
        self.assertIn("Android counted 1250MB of native heap. The nearest profile snapshot, 0s away, explains 900MB of it, and 250MB was allocated before it attached.", text)

    def test_report_warns_when_snapshots_stop_before_the_samples(self) -> None:
        native = {"times": [at("20:00:00"), at("20:01:00")], "live_mb": [10.0, 20.0], "groups": [{"name": "libhwui.so: Game.loadingReason", "series_mb": [10.0, 20.0]}]}
        samples = [{"time": at(clock), "pss_kb": 1024, "native_kb": 1024, "java_kb": 0, "graphics_kb": 0, "swap_kb": 0, "avail_kb": 0} for clock in ("20:00:00", "20:01:00", "20:30:00")]
        text = uma_mem.render_report({"started": at("20:00:00"), "ended": "Stopped", "samples": samples, "log_lines": [], "native": native})
        self.assertIn("20:01:00 Native profile has no snapshots after this time", text)

    def test_report_stays_quiet_when_snapshots_reach_the_last_sample(self) -> None:
        native = {"times": [at("20:00:00"), at("20:01:00")], "live_mb": [10.0, 20.0], "groups": [{"name": "libhwui.so: Game.loadingReason", "series_mb": [10.0, 20.0]}]}
        samples = [{"time": at("20:01:30"), "pss_kb": 1024, "native_kb": 1024, "java_kb": 0, "graphics_kb": 0, "swap_kb": 0, "avail_kb": 0}]
        text = uma_mem.render_report({"started": at("20:00:00"), "ended": "Stopped", "samples": samples, "log_lines": [], "native": native})
        self.assertNotIn("Warnings", text)

    def test_report_says_so_when_there_is_no_native_data(self) -> None:
        run = {"started": at("20:00:00"), "ended": "Stopped", "samples": [], "log_lines": [], "native": None}
        self.assertIn("No native profile", uma_mem.render_report(run))


if __name__ == "__main__":
    unittest.main()
