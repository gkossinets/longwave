#!/usr/bin/env python3
"""Unit tests for the pure helpers in longwave.py. No speaker needed.

Run with the project's virtualenv (it has SoCo, which longwave.py imports):
    .venv/bin/python -m unittest -v
"""
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock

import longwave

HERE = os.path.dirname(os.path.realpath(__file__))

STATIONS = {
    "helsinki": {"name": "Radio Helsinki", "url": "https://stream.radiohelsinki.fi/"},
    "fip": {"name": "FIP", "url": "https://icecast.radiofrance.fr/fip-hifi.aac"},
    "fipjazz": {"name": "FIP Jazz", "url": "https://icecast.radiofrance.fr/fipjazz-hifi.aac"},
}


class StreamUriTest(unittest.TestCase):
    def test_drops_https(self) -> None:
        self.assertEqual(longwave.stream_uri("https://stream.radiohelsinki.fi/"),
                         "x-rincon-mp3radio://stream.radiohelsinki.fi/")

    def test_drops_http(self) -> None:
        self.assertEqual(longwave.stream_uri("http://example.com:8000/live.mp3"),
                         "x-rincon-mp3radio://example.com:8000/live.mp3")

    def test_bare_host_gets_prefix(self) -> None:
        self.assertEqual(longwave.stream_uri("example.com/live"),
                         "x-rincon-mp3radio://example.com/live")


class ParseDaysTest(unittest.TestCase):
    def test_keywords(self) -> None:
        for spec in ("daily", "weekdays", "weekends", "once"):
            self.assertEqual(longwave.parse_days(spec), spec.upper())

    def test_keywords_case_insensitive(self) -> None:
        self.assertEqual(longwave.parse_days("Weekdays"), "WEEKDAYS")

    def test_day_list_sorted_and_deduplicated(self) -> None:
        # Sonos day codes: Sunday is 0.
        self.assertEqual(longwave.parse_days("fri,mon,wed,mon"), "ON_135")
        self.assertEqual(longwave.parse_days("sat,sun"), "ON_06")

    def test_full_names_and_spaces(self) -> None:
        self.assertEqual(longwave.parse_days("Monday, Tuesday"), "ON_12")

    def test_bad_day_exits(self) -> None:
        with self.assertRaises(SystemExit):
            longwave.parse_days("mon,xyz")


class DescribeRecurrenceTest(unittest.TestCase):
    def test_day_list(self) -> None:
        self.assertEqual(longwave.describe_recurrence("ON_135"), "Mon,Wed,Fri")

    def test_keyword(self) -> None:
        self.assertEqual(longwave.describe_recurrence("WEEKDAYS"), "Weekdays")

    def test_round_trip(self) -> None:
        self.assertEqual(longwave.describe_recurrence(longwave.parse_days("sun,sat")), "Sun,Sat")


class GetStationTest(unittest.TestCase):
    def setUp(self) -> None:
        patcher = mock.patch.object(longwave, "load_stations", return_value=STATIONS)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_exact_key(self) -> None:
        self.assertEqual(longwave.get_station("fip"), ("fip", STATIONS["fip"]))

    def test_exact_key_wins_over_partial(self) -> None:
        # "fip" is also a substring of "fipjazz"; the exact key must not be ambiguous.
        self.assertEqual(longwave.get_station("fip")[0], "fip")

    def test_partial_key(self) -> None:
        self.assertEqual(longwave.get_station("hel")[0], "helsinki")

    def test_partial_name_case_insensitive(self) -> None:
        self.assertEqual(longwave.get_station("JAZZ")[0], "fipjazz")

    def test_ambiguous_exits(self) -> None:
        with self.assertRaises(SystemExit):
            longwave.get_station("fi")

    def test_unknown_exits(self) -> None:
        with self.assertRaises(SystemExit):
            longwave.get_station("nope")


class ShippedStationsTest(unittest.TestCase):
    def test_stations_json_is_valid(self) -> None:
        stations = longwave.load_stations()
        self.assertTrue(stations)
        for key, s in stations.items():
            self.assertTrue(s["name"], key)
            self.assertTrue(s["url"].startswith(("http://", "https://")), key)


@unittest.skipUnless(os.path.exists(os.path.join(HERE, ".venv", "bin", "python")),
                     "no .venv; see Setup in README.md")
class WrapperTest(unittest.TestCase):
    def test_runs_through_symlink(self) -> None:
        # The README tells people to symlink the wrapper onto PATH, so it must
        # find its own folder through the link.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        link = os.path.join(tmp, "longwave")
        os.symlink(os.path.join(HERE, "longwave"), link)
        out = subprocess.run([link, "--help"], capture_output=True, text=True, cwd=tmp)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("usage: longwave", out.stdout)

    def test_runs_through_relative_symlink_chain(self) -> None:
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        os.symlink(os.path.join(HERE, "longwave"), os.path.join(tmp, "first"))
        os.mkdir(os.path.join(tmp, "bin"))
        os.symlink("../first", os.path.join(tmp, "bin", "longwave"))
        out = subprocess.run([os.path.join(tmp, "bin", "longwave"), "--help"],
                             capture_output=True, text=True, cwd="/")
        self.assertEqual(out.returncode, 0, out.stderr)

    def _scratch_repo(self, tmp: str) -> str:
        """Copy the wrapper into tmp/repo, so relative links end at a real file."""
        repo = os.path.join(tmp, "repo")
        os.makedirs(os.path.join(repo, "bin"))
        shutil.copy2(os.path.join(HERE, "longwave"), repo)
        os.symlink(os.path.join(HERE, "longwave.py"), os.path.join(repo, "longwave.py"))
        os.symlink(os.path.join(HERE, ".venv"), os.path.join(repo, ".venv"))
        return repo

    def test_relative_link_reached_through_directory_symlink(self) -> None:
        # e.g. ~/bin -> dotfiles/bin, and dotfiles/bin/longwave -> ../longwave.
        # "../" must resolve against the real folder, not the symlinked one.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        repo = self._scratch_repo(tmp)
        os.symlink("../longwave", os.path.join(repo, "bin", "longwave"))
        os.symlink(os.path.join(repo, "bin"), os.path.join(tmp, "bin"))
        out = subprocess.run([os.path.join(tmp, "bin", "longwave"), "--help"],
                             capture_output=True, text=True, cwd="/")
        self.assertEqual(out.returncode, 0, out.stderr)

    def test_ignores_cdpath(self) -> None:
        # With CDPATH exported, `cd relative/dir` can jump elsewhere and print
        # the folder on stdout, corrupting the resolved path.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        repo = self._scratch_repo(tmp)
        os.symlink("../longwave", os.path.join(repo, "bin", "longwave"))
        os.makedirs(os.path.join(tmp, "decoy", "bin"))
        env = dict(os.environ, CDPATH=os.path.join(tmp, "decoy"))
        out = subprocess.run(["bin/longwave", "--help"], capture_output=True, text=True,
                             cwd=repo, env=env)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("usage: longwave", out.stdout)

    def test_works_without_readlink_f(self) -> None:
        # macOS before 12.3 has a readlink with no -f option. Simulate it with a
        # shim that rejects -f, so the wrapper must not depend on it.
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        shim_dir = os.path.join(tmp, "shim")
        os.mkdir(shim_dir)
        real = shutil.which("readlink")
        with open(os.path.join(shim_dir, "readlink"), "w") as f:
            f.write('#!/bin/sh\n[ "$1" = "-f" ] && { echo "readlink: illegal option -- f" >&2; exit 1; }\n'
                    f'exec {real} "$@"\n')
        os.chmod(os.path.join(shim_dir, "readlink"), 0o755)
        link = os.path.join(tmp, "longwave")
        os.symlink(os.path.join(HERE, "longwave"), link)
        env = dict(os.environ, PATH=shim_dir + os.pathsep + os.environ["PATH"])
        out = subprocess.run([link, "--help"], capture_output=True, text=True, cwd=tmp, env=env)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertIn("usage: longwave", out.stdout)


if __name__ == "__main__":
    unittest.main()
