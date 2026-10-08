"""Offline, fail-closed HD import. Existing model bytes remain unchanged."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile
from PIL import Image

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'src/main/resources/assets/theprisons'
SAFE = re.compile(r'[a-z0-9_]+(?:/[a-z0-9_]+)+\Z')


def safe(value):
    if not isinstance(value, str) or not SAFE.fullmatch(value):
        raise ValueError(f'Invalid asset ID: {value!r}')
    return value


def contained(root, name):
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError('Path escapes source directory')
    return path


def png(path):
    with Image.open(path) as image:
        if image.format != 'PNG' or image.mode != 'RGBA' or getattr(image, 'n_frames', 1) != 1:
            raise ValueError('Expected single-frame RGBA PNG')
        image.load()
        return image.copy()


def tile(source, spec):
    image = png(source)
    if spec:
        # Explicit rectangles support arbitrary layouts; grid supports margins and gutters.
        if 'rect' in spec:
            box = spec['rect']
        else:
            col, row = spec['cell']
            margin = spec.get('margin', [0, 0])
            gap = spec.get('gap', [0, 0])
            if min(col, row, *margin, *gap) < 0:
                raise ValueError('Negative grid coordinates')
            x, y = margin[0] + col * (256 + gap[0]), margin[1] + row * (256 + gap[1])
            box = [x, y, x + 256, y + 256]
        if len(box) != 4 or any(type(n) is not int for n in box):
            raise ValueError('Rectangle needs four integers')
        x, y, right, bottom = box
        if x < 0 or y < 0 or right > image.width or bottom > image.height:
            raise ValueError('Tile outside sheet')
        image = image.crop(box)
    if image.size != (256, 256):
        raise ValueError('Texture must be exactly 256x256; no automatic scaling')
    low, high = image.getchannel('A').getextrema()
    if low != 0 or high == 0:
        raise ValueError('Texture needs transparent and visible pixels')
    stream = io.BytesIO()
    image.save(stream, format='PNG')
    return stream.getvalue()


def catalog(base=BASE):
    result = {}
    for definition in sorted((base / 'items/prisons').rglob('*.json')):
        ident = definition.relative_to(base / 'items/prisons').with_suffix('').as_posix()
        model = base / 'models/item/prisons' / (ident + '.json')
        body = json.loads(model.read_text())
        textures = body.get('textures', {})
        if not isinstance(textures, dict):
            raise ValueError(f'Invalid model textures: {ident}')
        targets = []
        for texture in textures.values():
            if not isinstance(texture, str):
                raise ValueError(f'Invalid texture reference: {ident}')
            if texture.startswith('theprisons:'):
                target = safe(texture.split(':', 1)[1])
                if not (base / 'textures' / (target + '.png')).is_file():
                    raise ValueError(f'Missing Classic fallback: {target}')
                targets.append(target)
        result[ident] = {'model': model, 'targets': targets}
    return result


def seed(base=BASE):
    rows = []
    for ident, info in catalog(base).items():
        family, variant = ident.split('/', 1)
        rows.append(dict(technical_item_id=ident, item_family=family, variant=variant,
                         visual_asset_id='', source_image='', target_texture=info['targets'][0] if len(info['targets']) == 1 else '',
                         sha256='', validation_status='NEEDS_REFERENCE'))
    return {'schema_version': 1, 'assets': rows}


def build(registry, sources, base=BASE):
    if registry.get('schema_version') != 1:
        raise ValueError('Unsupported schema version')
    known = catalog(base)
    rows = registry['assets']
    identities, prepared, visuals, targets, hashes = set(), {}, {}, {}, {}
    for row in rows:
        ident = safe(row['technical_item_id'])
        if ident in identities or ident not in known:
            raise ValueError(f'Duplicate or unknown item: {ident}')
        identities.add(ident)
        family, variant = ident.split('/', 1)
        if (row['item_family'], row['variant']) != (family, variant):
            raise ValueError(f'Wrong family/variant: {ident}')
        if row['validation_status'] != 'APPROVED':
            continue
        visual = safe(row['visual_asset_id'])
        target = safe(row['target_texture'])
        if target not in known[ident]['targets']:
            raise ValueError(f'Target differs from existing model: {ident}')
        if variant.startswith('random_') and visual != family + '/random':
            raise ValueError(f'Random variants require canonical artwork: {ident}')
        data = tile(contained(sources, row['source_image']), row.get('sprite'))
        digest = hashlib.sha256(data).hexdigest()
        if row['sha256'] != digest:
            raise ValueError(f'Unverified hash: {ident}; candidate sha256={digest}')
        if visual in visuals and visuals[visual] != digest:
            raise ValueError(f'Conflicting shared artwork: {visual}')
        if target in targets and targets[target] != digest:
            raise ValueError(f'Conflicting target: {target}')
        if digest in hashes and hashes[digest] != visual:
            raise ValueError(f'Duplicate artwork under different IDs: {visual}')
        visuals[visual], targets[target], hashes[digest] = digest, digest, visual
        prepared[ident] = (target, data)
    complete = {family for family in {i.split('/')[0] for i in known}
                if all(i in prepared for i in known if i.startswith(family + '/'))}
    files, manifest = {}, []
    for row in rows:
        entry = dict(row)
        ident = row['technical_item_id']
        if row['item_family'] in complete:
            target, data = prepared[ident]
            files['assets/theprisons/textures/' + target + '.png'] = data
            entry['validation_status'] = 'VALIDATED'
        else:
            entry['validation_status'] = 'CLASSIC_FALLBACK'
        manifest.append(entry)
    files['pack.mcmeta'] = json.dumps({'pack': {'description': 'ThePrisons HD V4 validated overlay', 'min_format': 1, 'max_format': 999}}, sort_keys=True).encode()
    files['manifest.json'] = json.dumps({'schema_version': 1, 'assets': manifest}, indent=2, sort_keys=True).encode()
    return files


def write_pack(files, destination):
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation prevents accidentally overwriting reviewed artifacts.
    with destination.open('xb') as output, zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    init = commands.add_parser('seed')
    init.add_argument('output', type=Path)
    pack = commands.add_parser('build')
    pack.add_argument('registry', type=Path)
    pack.add_argument('sources', type=Path)
    pack.add_argument('output', type=Path)
    args = parser.parse_args()
    if args.command == 'seed':
        with args.output.open('x') as output:
            json.dump(seed(), output, indent=2)
    else:
        write_pack(build(json.loads(args.registry.read_text()), args.sources), args.output)


if __name__ == '__main__':
    main()
