"""Checks isolated shell helpers and synthetic samples; never runs the soak loop."""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(sys.argv.pop(1)).read_text()


def function(name):
    return re.search(r"^" + name + r"\(\) \{\n.*?^\}", SCRIPT, re.M | re.S).group(0)


class SoakHelpersTest(unittest.TestCase):
    def run_shell(self, code, **values):
        return subprocess.run(["bash", "-c", "set -euo pipefail\n" + code],
                              env={**os.environ, **{k: str(v) for k, v in values.items()}},
                              capture_output=True, text=True, timeout=5)

    def test_diagnostic_client_has_a_deadline(self):
        with tempfile.TemporaryDirectory() as root:
            client = Path(root) / "diagnostic"
            client.write_text("#!/usr/bin/perl\nsleep 4;\n")
            client.chmod(0o700)
            result = self.run_shell(function("heap_diagnostic") + "\nif heap_diagnostic GC.run; then exit 9; fi",
                                    HEAP_TIMEOUT="0.05", JCMD=client, PID="123")
            self.assertEqual(0, result.returncode, result.stderr)

    def test_empty_port_produces_the_explanatory_message(self):
        with tempfile.TemporaryDirectory() as root:
            probe = Path(root) / "lsof"
            probe.write_text("#!/bin/sh\nexit 1\n")
            probe.chmod(0o700)
            lines = [line for line in SCRIPT.splitlines()
                     if line.startswith("LISTENERS=") or line.startswith('[ "$LISTENERS"')]
            result = self.run_shell("\n".join(lines), PATH=root + ":" + os.environ["PATH"], PID="123")
            self.assertEqual(2, result.returncode)
            self.assertIn("must own the automation endpoint", result.stderr)

    def test_unused_key_modifier_is_accepted_and_sent_as_zero(self):
        phases = "open_player switch_episode minimize restore close_player home library search start_download pause_download resume_download delete_download sign_in_check".split()
        with tempfile.TemporaryDirectory() as root:
            plan = Path(root) / "steps.tsv"
            plan.write_text("".join(phase + ("\tscroll\t1\t1\t-10\t0\n" if phase in ("home", "library", "search")
                                            else "\tkey\t27\t-\t-\t0\n") for phase in phases))
            validation = SCRIPT[SCRIPT.index("awk -F '\\t'"):SCRIPT.index('TOKEN=$(cat')]
            result = self.run_shell(validation, PLAN=plan)
            self.assertEqual(0, result.returncode, result.stderr)
            curl = Path(root) / "curl"
            curl.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
            curl.chmod(0o700)
            result = self.run_shell(function("request") + "\nrequest key 27 - -",
                                    PATH=root + ":" + os.environ["PATH"], TOKEN="test", BASE="http://127.0.0.1:18766")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("mod=0", result.stdout)

    def test_empty_plan_fields_are_rejected_before_actions(self):
        phases = "open_player switch_episode minimize restore close_player home library search start_download pause_download resume_download delete_download sign_in_check".split()
        rows = [phase + ("\tscroll\t1\t1\t-10\t0" if phase in ("home", "library", "search")
                         else "\tkey\t27\t-\t-\t0") for phase in phases[:-1]]
        validation = SCRIPT[SCRIPT.index("awk -F '\\t'"):SCRIPT.index('TOKEN=$(cat')]
        with tempfile.TemporaryDirectory() as root:
            plan = Path(root) / "steps.tsv"
            for command in ("info", "type"):
                for column in (2, 3, 4):
                    with self.subTest(command=command, column=column):
                        fields = ["sign_in_check", command, "text" if command == "type" else "-", "-", "-", "5"]
                        fields[column] = ""
                        plan.write_text("\n".join(rows + ["\t".join(fields)]) + "\n")
                        result = self.run_shell(validation, PLAN=plan)
                        self.assertEqual(2, result.returncode, result.stderr)
                        self.assertIn("Invalid or incomplete plan", result.stderr)

    def test_info_and_type_placeholders_keep_all_six_fields(self):
        phases = "open_player switch_episode minimize restore close_player home library search start_download pause_download resume_download delete_download sign_in_check".split()
        rows = [phase + ("\tscroll\t1\t1\t-10\t0" if phase in ("home", "library", "search")
                         else "\tkey\t27\t-\t-\t0") for phase in phases[:-1]]
        rows += ["sign_in_check\tinfo\t-\t-\t-\t5", "sign_in_check\ttype\tspace in text\t-\t-\t7"]
        validation = SCRIPT[SCRIPT.index("awk -F '\\t'"):SCRIPT.index('TOKEN=$(cat')]
        with tempfile.TemporaryDirectory() as root:
            plan = Path(root) / "steps.tsv"
            plan.write_text("\n".join(rows) + "\n")
            result = self.run_shell(validation + "\nwhile IFS=$'\\t' read -r phase command a b c settle; do "
                                    "if [ \"$phase\" = sign_in_check ]; then printf '%s|%s|%s\\n' \"$command\" \"$a\" \"$settle\"; fi; done < \"$PLAN\"", PLAN=plan)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("info|-|5", result.stdout)
            self.assertIn("type|space in text|7", result.stdout)

    def test_numeric_arguments_use_decimal_even_with_leading_zeroes(self):
        validation = SCRIPT[SCRIPT.index('for number in '):SCRIPT.index('HEAP_TIMEOUT=$(awk')]
        result = self.run_shell(validation + '\nprintf "%s|%s|%s\\n" "$DURATION" "$INTERVAL" "$PID"',
                                DURATION="00600", INTERVAL="060", PID="00123")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("600|60|123\n", result.stdout)

    def test_settling_time_with_a_leading_zero_is_decimal(self):
        result = self.run_shell(function("wait_sampled") + "\nwait_sampled 08", END="0")
        self.assertEqual(0, result.returncode, result.stderr)

    def summary(self, rss, heap, status="ok"):
        with tempfile.TemporaryDirectory() as root:
            csv = Path(root) / "samples.csv"
            csv.write_text("timestamp_utc,elapsed_seconds,cycle,phase,rss_kib,heap_after_gc_bytes,heap_status\n" +
                           "".join(f"test,{i * 10},1,home,{r},{h},{status}\n" for i, (r, h) in enumerate(zip(rss, heap))))
            result = self.run_shell(function("summary") + "\nsummary", CSV=csv, WARMUP="0", INTERVAL="10", DURATION="60", CYCLE="1")
            return result.returncode, result.stdout

    def test_flat_samples_pass(self):
        code, output = self.summary([10000] * 7, [10000] * 7)
        self.assertEqual(0, code, output)
        self.assertIn("result=WITHIN_LIMIT", output)

    def test_small_but_steady_resident_growth_fails(self):
        code, output = self.summary([10000 + i * 10 for i in range(7)], [10000] * 7)
        self.assertEqual(1, code, output)
        self.assertIn("rss_trend=upward", output)

    def test_heap_growth_fails(self):
        code, output = self.summary([10000] * 7, [10000] * 3 + [12000] * 4)
        self.assertEqual(1, code, output)

    def test_missing_heap_stays_inconclusive(self):
        code, output = self.summary([10000] * 7, [""] * 7, "unavailable")
        self.assertEqual(3, code, output)
        self.assertIn("result=INCONCLUSIVE", output)


if __name__ == "__main__":
    unittest.main()
