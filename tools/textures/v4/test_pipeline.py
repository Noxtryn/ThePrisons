"""Regression tests for the offline V4 asset gate.

Run with: python3 -m unittest tools/textures/v4/test_pipeline.py
"""
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

from PIL import Image


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("v4_pipeline", HERE / "pipeline.py")
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)
ROOT = HERE.parents[2]
REGISTRY = ROOT / "docs/textures/V4_ASSET_REGISTRY.json"


def rgba_png(transparent=True):
    image = Image.new("RGBA", (256, 256), (5, 20, 30, 255))
    if transparent:
        image.putpixel((0, 0), (0, 0, 0, 0))
    data = io.BytesIO()
    image.save(data, "PNG")
    return data.getvalue()


class V4PipelineTest(unittest.TestCase):
    def test_registry_is_exact_catalog_seed(self):
        self.assertEqual(json.loads(REGISTRY.read_text()), pipeline.seed())

    def test_empty_approval_build_is_classic_only(self):
        registry = json.loads(REGISTRY.read_text())
        with tempfile.TemporaryDirectory() as temp:
            files = pipeline.build(registry, Path(temp))
        self.assertEqual(set(files), {"pack.mcmeta", "manifest.json"})
        manifest = json.loads(files["manifest.json"])
        self.assertEqual(len(manifest["assets"]), 465)
        self.assertEqual({r["validation_status"] for r in manifest["assets"]}, {"CLASSIC_FALLBACK"})

    def test_rejects_opaque_artwork(self):
        registry = copy.deepcopy(json.loads(REGISTRY.read_text()))
        row = registry["assets"][0]
        row.update(visual_asset_id="book/elite", source_image="elite.png", validation_status="APPROVED")
        data = rgba_png(transparent=False)
        row["sha256"] = hashlib.sha256(data).hexdigest()
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / "elite.png").write_bytes(data)
            with self.assertRaisesRegex(ValueError, "transparent"):
                pipeline.build(registry, source)

    def test_rejects_unverified_hash(self):
        registry = copy.deepcopy(json.loads(REGISTRY.read_text()))
        row = registry["assets"][0]
        row.update(visual_asset_id="book/elite", source_image="elite.png", validation_status="APPROVED", sha256="0" * 64)
        with tempfile.TemporaryDirectory() as temp:
            source = Path(temp)
            (source / "elite.png").write_bytes(rgba_png())
            with self.assertRaisesRegex(ValueError, "Unverified hash"):
                pipeline.build(registry, source)


if __name__ == "__main__":
    unittest.main()
