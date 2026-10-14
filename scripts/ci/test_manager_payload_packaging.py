"""Exercise manager payload packaging with real ZIP files, without Android tools."""

import sys
import tempfile
import unittest
import warnings
from pathlib import Path
from unittest.mock import patch
from zipfile import ZIP_DEFLATED, ZipFile

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import repack_apk


class ManagerPayloadPackagingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="manager-payload-test-")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "manager").mkdir()
        self.config = self.root / "manager" / "ksud-library-name.txt"
        self.config.write_text("libruntime.so\n", encoding="utf-8")
        self.root_patch = patch.object(repack_apk, "workspace_root", return_value=self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.apk = self.root / "input.apk"
        self.output = self.root / "output.apk"
        self.binary = self.root / "ksud"
        self.binary.write_bytes(b"new native payload")

    def make_apk(self, entries, marker=b"libruntime.so\n"):
        with ZipFile(self.apk, "w", compression=ZIP_DEFLATED) as apk:
            apk.writestr("classes.dex", b"manager code fixture")
            if marker is not None:
                apk.writestr("assets/runtime/payload.txt", marker)
            for name, data in entries:
                apk.writestr(name, data)

    def test_injects_payload_into_current_ci_apk_without_existing_payload(self):
        self.make_apk([("lib/arm64-v8a/libother.so", b"other library")])
        self.assertEqual(repack_apk.collect_existing_ksud_arches(self.apk), [])
        repack_apk.repack_apk(
            self.apk, self.output, ["arm64-v8a"], {"arm64-v8a": self.binary}, None
        )
        with ZipFile(self.output) as output:
            self.assertEqual(output.read("lib/arm64-v8a/libruntime.so"), self.binary.read_bytes())
            self.assertEqual(output.read("lib/arm64-v8a/libother.so"), b"other library")
            self.assertEqual(output.read("classes.dex"), b"manager code fixture")
            self.assertEqual(output.read("assets/runtime/payload.txt"), b"libruntime.so\n")
            self.assertNotIn("lib/arm64-v8a/libksud.so", output.namelist())
        repack_apk.assert_required_libs(self.output, ["arm64-v8a"])

    def test_missing_marker_rejects_old_ci_apk_even_with_new_binary(self):
        self.make_apk([], marker=None)
        with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
            repack_apk.repack_apk(
                self.apk, self.output, ["arm64-v8a"], {"arm64-v8a": self.binary}, None
            )
        self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
            repack_apk.collect_existing_ksud_arches(self.apk)
        with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
            repack_apk.assert_required_libs(self.apk, ["arm64-v8a"])

    def test_wrong_or_invalid_marker_is_rejected_even_with_current_payload(self):
        for marker in (b"libksud.so\n", b"libdifferent.so\n", b"", b"\xff"):
            with self.subTest(marker=marker):
                self.make_apk([("lib/arm64-v8a/libruntime.so", b"payload")], marker=marker)
                with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
                    repack_apk.repack_apk(
                        self.apk, self.output, ["arm64-v8a"], {"arm64-v8a": self.binary}, None
                    )
                self.assertFalse(self.output.exists())
                with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
                    repack_apk.collect_existing_ksud_arches(self.apk)
                with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
                    repack_apk.assert_required_libs(self.apk, ["arm64-v8a"])

    def test_replaces_payload_without_duplicate_zip_entries(self):
        # Even a malformed input containing duplicate payload entries is reduced
        # to one replacement entry for the selected ABI.
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            self.make_apk([
                ("lib/arm64-v8a/libruntime.so", b"old payload"),
                ("lib/arm64-v8a/libruntime.so", b"another old payload"),
            ])
        repack_apk.repack_apk(
            self.apk, self.output, ["arm64-v8a"], {"arm64-v8a": self.binary}, None
        )
        with ZipFile(self.output) as output:
            self.assertEqual(output.namelist().count("lib/arm64-v8a/libruntime.so"), 1)
            self.assertEqual(output.read("lib/arm64-v8a/libruntime.so"), self.binary.read_bytes())

    def test_can_keep_existing_current_payload_without_local_binary(self):
        self.make_apk([("lib/arm64-v8a/libruntime.so", b"existing payload")])
        self.assertEqual(repack_apk.collect_existing_ksud_arches(self.apk), ["arm64-v8a"])
        repack_apk.repack_apk(self.apk, self.output, ["arm64-v8a"], {}, None)
        with ZipFile(self.output) as output:
            self.assertEqual(output.read("lib/arm64-v8a/libruntime.so"), b"existing payload")
        repack_apk.assert_required_libs(self.output, ["arm64-v8a"])

    def test_missing_payload_fails_before_writing_output(self):
        self.make_apk([("lib/arm64-v8a/libother.so", b"other library")])
        with self.assertRaisesRegex(RuntimeError, r"Missing libruntime\.so.*arm64-v8a"):
            repack_apk.repack_apk(self.apk, self.output, ["arm64-v8a"], {}, None)
        self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(RuntimeError, r"Missing libruntime\.so.*arm64-v8a"):
            repack_apk.assert_required_libs(self.apk, ["arm64-v8a"])

    def test_filters_native_entries_to_selected_abi(self):
        self.make_apk([
            ("lib/arm64-v8a/libruntime.so", b"arm64 payload"),
            ("lib/arm64-v8a/libother.so", b"arm64 library"),
            ("lib/x86_64/libruntime.so", b"x86 payload"),
            ("lib/x86_64/libother.so", b"x86 library"),
        ])
        repack_apk.repack_apk(self.apk, self.output, ["arm64-v8a"], {}, None)
        with ZipFile(self.output) as output:
            self.assertEqual(set(output.namelist()), {
                "classes.dex", "assets/runtime/payload.txt",
                "lib/arm64-v8a/libruntime.so", "lib/arm64-v8a/libother.so"
            })

    def test_rejects_legacy_apk_instead_of_renaming_its_payload(self):
        self.make_apk([("lib/arm64-v8a/libksud.so", b"legacy payload")])
        # A fresh local binary cannot fix the old Manager's runtime path.
        for binaries in ({}, {"arm64-v8a": self.binary}):
            with self.subTest(binaries=bool(binaries)):
                with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
                    repack_apk.repack_apk(self.apk, self.output, ["arm64-v8a"], binaries, None)
                self.assertFalse(self.output.exists())
        with self.assertRaisesRegex(RuntimeError, "rebuild the Manager"):
            repack_apk.collect_existing_ksud_arches(self.apk)

    def test_rejects_mixed_current_and_legacy_payloads(self):
        for legacy_abi in ("arm64-v8a", "x86_64"):
            with self.subTest(legacy_abi=legacy_abi):
                self.make_apk([
                    ("lib/arm64-v8a/libruntime.so", b"current payload"),
                    (f"lib/{legacy_abi}/libksud.so", b"legacy payload"),
                ])
                with self.assertRaisesRegex(RuntimeError, "legacy libksud.so"):
                    repack_apk.repack_apk(self.apk, self.output, ["arm64-v8a"], {}, None)
                self.assertFalse(self.output.exists())
                with self.assertRaisesRegex(RuntimeError, "legacy libksud.so"):
                    repack_apk.assert_required_libs(self.apk, ["arm64-v8a"])

    def test_all_packaging_helpers_use_the_shared_filename(self):
        self.config.write_text("libnew_payload1.so\n", encoding="utf-8")
        self.make_apk(
            [("lib/arm64-v8a/libnew_payload1.so", b"old payload")],
            marker=b"libnew_payload1.so\r\n",
        )
        self.assertEqual(repack_apk.collect_existing_ksud_arches(self.apk), ["arm64-v8a"])
        repack_apk.repack_apk(
            self.apk, self.output, ["arm64-v8a"], {"arm64-v8a": self.binary}, None
        )
        repack_apk.assert_required_libs(self.output, ["arm64-v8a"])
        with ZipFile(self.output) as output:
            self.assertEqual(output.read("lib/arm64-v8a/libnew_payload1.so"), self.binary.read_bytes())
            self.assertNotIn("lib/arm64-v8a/libruntime.so", output.namelist())

    def test_rejects_invalid_configured_filenames(self):
        for name in ("", "../libruntime.so", "lib/path.so", "libbad-name.so", "lib.so", "libx.so\nliby.so"):
            with self.subTest(name=name):
                self.config.write_text(name, encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Invalid manager native payload filename"):
                    repack_apk.ksud_library_name()


if __name__ == "__main__":
    unittest.main()
