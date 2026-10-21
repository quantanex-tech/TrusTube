#!/usr/bin/env python3
"""Convert the supplied SVG subset to vectors, with matching API 23 raster fallbacks.
Requires ImageMagick's `magick` command. No assets are downloaded.
"""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'assets/Basil Icons (Community)'
ICONS = {
    'home': 'Outline/General/Home.svg',
    'favorite': 'Outline/Status/Star.svg',
    'library': 'Outline/Files/Folder.svg',
    'favorite_filled': 'Solid/Status/Star.svg',
    'search': 'Outline/Interface/Search.svg',
    'channels': 'Outline/Interface/Apps.svg',
    'parent': 'Outline/Communication/User.svg',
    'back': 'Outline/Interface/Arrow left.svg',
    'more': 'Outline/Interface/Other 2.svg',
    'play': 'Solid/Media/Play.svg',
    'pause': 'Solid/Media/Pause.svg',
}
ANDROID = 'http://schemas.android.com/apk/res/android'
ET.register_namespace('android', ANDROID)

def attr(name):
    return '{' + ANDROID + '}' + name

for name, relative in ICONS.items():
    source = SOURCE / relative
    svg = ET.parse(source).getroot()
    box = svg.get('viewBox', '0 0 24 24').split()
    vector = ET.Element('vector', {
        attr('width'): '24dp', attr('height'): '24dp',
        attr('viewportWidth'): box[2], attr('viewportHeight'): box[3],
    })
    for path in svg:
        if not path.tag.endswith('path') or path.get('transform'):
            raise ValueError('Unsupported SVG element or transform: ' + str(source))
        values = {attr('pathData'): path.get('d'),
                  attr('fillColor'): '#00000000' if path.get('fill') == 'none' else '#FF000000'}
        if path.get('fill-rule') == 'evenodd':
            values[attr('fillType')] = 'evenOdd'
        if path.get('stroke'):
            values[attr('strokeColor')] = '#FF000000'
            values[attr('strokeWidth')] = path.get('stroke-width', '1')
        ET.SubElement(vector, 'path', values)
    ET.indent(vector)
    output = ROOT / 'app/src/main/res/drawable-anydpi-v24' / ('ic_' + name + '.xml')
    output.parent.mkdir(exist_ok=True)
    output.write_text('<?xml version="1.0" encoding="utf-8"?>\n'
                      '<!-- Converted from user-supplied Basil Icons: ' + relative + ' -->\n'
                      + ET.tostring(vector, encoding='unicode') + '\n')
    fallback = ROOT / 'app/src/main/res/drawable-xxxhdpi' / ('ic_' + name + '.png')
    fallback.parent.mkdir(exist_ok=True)
    subprocess.run(['magick', '-background', 'none', str(source), '-resize', '96x96', str(fallback)], check=True)
    legacy = ROOT / 'app/src/main/res/drawable' / output.name
    if legacy.exists() and 'Converted from user-supplied Basil Icons:' in legacy.read_text():
        legacy.unlink()
print('Converted', len(ICONS), 'icons; vectors for API 24+, raster fallback for API 23.')
