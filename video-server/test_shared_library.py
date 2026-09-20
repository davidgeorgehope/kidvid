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

    def test_safe_device_id(self):
        self.assertEqual(self.server.safe_device_id("iphone-yellow"), "iphone-yellow")
        self.assertEqual(self.server.safe_device_id("pixel-abc12345"), "pixel-abc12345")
        self.assertIsNone(self.server.safe_device_id("../x"))
        self.assertIsNone(self.server.safe_device_id("has space"))

    def test_parent_delete_guard(self):
        h = object.__new__(self.server.KidVidHandler)
        h.headers = {}
        auth = self.server.KidVidHandler._parent_delete_authorized
        self.assertFalse(auth(h, {}))
        self.assertFalse(auth(h, {"parent": ["0"]}))
        self.assertTrue(auth(h, {"parent": ["1"]}))
        self.assertTrue(auth(h, {"parent": ["true"]}))
        h.headers = {"X-KidVid-Action": "parent-delete"}
        self.assertTrue(auth(h, {}))
        h.headers = {"X-KidVid-Action": "sync"}
        self.assertFalse(auth(h, {}))


if __name__ == "__main__":
    unittest.main()
