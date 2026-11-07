import fs from 'node:fs';
import path from 'node:path';

const [root, destination] = process.argv.slice(2);
if (!root || !destination) {
  throw new Error('Usage: node prepare-musicbee-playlists.mjs <MusicBee Playlists folder> <output.json>');
}

function filesUnder(directory) {
  return fs.readdirSync(directory, { withFileTypes: true }).flatMap(entry => {
    const full = path.join(directory, entry.name);
    return entry.isDirectory() ? filesUnder(full) : [full];
  });
}

function readMbp(file) {
  const bytes = fs.readFileSync(file);
  let offset = 0;
  function check(count) {
    if (count < 0 || offset + count > bytes.length) throw new Error(`Truncated playlist: ${file}`);
  }
  function int32() { check(4); const value = bytes.readInt32LE(offset); offset += 4; return value; }
  function string() {
    let length = 0;
    let shift = 0;
    for (;;) {
      check(1);
      const byte = bytes[offset++];
      length |= (byte & 127) << shift;
      if (!(byte & 128)) break;
      shift += 7;
      if (shift >= 28) throw new Error(`Invalid string length: ${file}`);
    }
    check(length);
    const result = new TextDecoder('utf-8', { fatal: true }).decode(bytes.subarray(offset, offset + length));
    offset += length;
    return result;
  }
  if (int32() !== 4) throw new Error(`Unsupported MusicBee playlist version: ${file}`);
  check(3);
  const headerLength = bytes.readUIntLE(offset, 3);
  offset += 3;
  check(headerLength);
  offset += headerLength;
  int32(); int32(); int32();
  const count = int32();
  if (count < 0 || count > 100000) throw new Error(`Invalid track count: ${file}`);
  const entries = [];
  for (let index = 0; index < count; index++) {
    entries.push(string());
    int32();
  }
  if (offset !== bytes.length) throw new Error(`Unexpected trailing playlist data: ${file}`);
  return entries;
}

function readM3u(file) {
  const bytes = fs.readFileSync(file);
  const text = bytes[0] === 0xff && bytes[1] === 0xfe ? bytes.subarray(2).toString('utf16le') : bytes.toString('utf8');
  return text.replace(/^\uFEFF/, '').split(/\r?\n/).map(line => line.trim())
    .filter(line => line && !line.startsWith('#'));
}

const playlists = [];
const unsupported = [];
for (const file of filesUnder(root).sort()) {
  const extension = path.extname(file).toLowerCase();
  const relative = path.relative(root, file).replaceAll('\\', '/');
  if (extension === '.xautopf') {
    unsupported.push({ file: relative, reason: 'Smart definition requires a MusicBee M3U/XML track-list export' });
    continue;
  }
  if (!['.mbp', '.m3u', '.m3u8'].includes(extension)) continue;
  const entries = extension === '.mbp' ? readMbp(file) : readM3u(file);
  playlists.push({
    sourceKey: `musicbee:${relative.toLowerCase()}`,
    name: path.basename(file, extension),
    entries,
  });
}
const bundle = { version: 1, playlists, unsupported };
fs.writeFileSync(destination, JSON.stringify(bundle, null, 2), 'utf8');
console.log(JSON.stringify({
  playlists: playlists.length,
  tracks: playlists.reduce((total, playlist) => total + playlist.entries.length, 0),
  smartDefinitionsNotExported: unsupported.length,
  bytes: fs.statSync(destination).size,
}, null, 2));
for (const playlist of playlists) console.log(`${playlist.name}: ${playlist.entries.length} entries`);
