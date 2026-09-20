#!/usr/bin/env python3
"""Smoke tests for shared-library GC + ack filtering (no network)."""

import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock


class SharedLibraryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name
        # Import server module with KIDVID_DIR pointed at tmp
        os.environ["KIDVID_DIR"] = self.dir
        import importlib
        import server
        importlib.reload(server)
        self.server = server

    def tearDown(self):
        self.tmp.cleanup()

    def _touch_mp4(self, name, age_days=0):
        path = Path(self.dir) / name
        path.write_bytes(b"fake-mp4")
        if age_days:
            old = time.time() - age_days * 86400
            os.utime(path, (old, old))
        return path

    def test_gc_removes_old_mp4_and_jpg_keeps_state_files(self):
        old = self._touch_mp4("old.mp4", age_days=8)
        thumb = Path(self.dir) / "old.jpg"
        thumb.write_bytes(b"jpg")
        os.utime(thumb, (old.stat().st_mtime, old.stat().st_mtime))
        fresh = self._touch_mp4("fresh.mp4", age_days=1)
        (Path(self.dir) / "deletes.json").write_text("{}\n")
        (Path(self.dir) / "acks.json").write_text("{}\n")
        (Path(self.dir) / "nox-home.json").write_text("{}\n")

        removed = self.server.gc_library()
        self.assertEqual(removed, ["old.mp4"])
        self.assertFalse(old.exists())
        self.assertFalse(thumb.exists())
        self.assertTrue(fresh.exists())
        self.assertTrue((Path(self.dir) / "deletes.json").exists())
        self.assertTrue((Path(self.dir) / "acks.json").exists())
        self.assertTrue((Path(self.dir) / "nox-home.json").exists())

    def test_ack_filters_video_list(self):
        self._touch_mp4("a.mp4")
        self._touch_mp4("b.mp4")
        with self.server.ACKS_LOCK:
            self.server.save_acks({"pixel-1": ["a.mp4"]})

        names_all = [f.name for f in self.server.iter_library_mp4s()]
        self.assertEqual(names_all, ["a.mp4", "b.mp4"])

        with self.server.ACKS_LOCK:
            acked = set(self.server.load_acks().get("pixel-1", []))
        filtered = [f.name for f in self.server.iter_library_mp4s() if f.name not in acked]
        self.assertEqual(filtered, ["b.mp4"])

    def test_pending_delete_hidden_from_listing(self):
        self._touch_mp4("gone.mp4")
        self._touch_mp4("keep.mp4")
        with self.server.DELETES_LOCK:
            self.server.save_deletes({"pixel-1": ["gone.mp4"], "phone": [], "fire": []})

        hidden = self.server.hidden_from_listing("pixel-1")
        filtered = [f.name for f in self.server.iter_library_mp4s() if f.name not in hidden]
        self.assertEqual(filtered, ["keep.mp4"])
        self.assertEqual(self.server.hidden_from_listing("iphone-1"), set())

    def test_ack_delete_records_ack(self):
        self.server.add_ack_filenames(["pixel-1"], "tee-only.mp4")
        with self.server.ACKS_LOCK:
            self.assertIn("tee-only.mp4", self.server.load_acks().get("pixel-1", []))
        hidden = self.server.hidden_from_listing("pixel-1")
        self.assertIn("tee-only.mp4", hidden)

    def test_safe_device_id(self):
        self.assertEqual(self.server.safe_device_id("iphone-yellow"), "iphone-yellow")
        self.assertEqual(self.server.safe_device_id("pixel-abc12345"), "pixel-abc12345")
        self.assertIsNone(self.server.safe_device_id("../x"))
        self.assertIsNone(self.server.safe_device_id("has space"))


if __name__ == "__main__":
    unittest.main()
