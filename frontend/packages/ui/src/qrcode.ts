/**
 * A small, dependency-free QR Code encoder (ISO/IEC 18004): byte mode only, error-correction level L, version
 * auto-selected to fit the data (1..20, far more than any URL this kit encodes needs). The kiosk's printer-failure
 * fallback (FR-ISS-016) shows this as the visitor's only way to find their ticket, so it must never depend on a
 * third-party package or network access to render. Ported from the reference algorithm (ISO/IEC 18004) and checked
 * against known-good output in `qrcode.test.ts`.
 */

// ---- GF(256) arithmetic (primitive polynomial 0x11d, the one QR uses) --------------------------------------------

const EXP_TABLE = buildExpTable();
const LOG_TABLE = buildLogTable(EXP_TABLE);

function buildExpTable(): number[] {
  const table = new Array<number>(256);
  for (let i = 0; i < 8; i++) table[i] = 1 << i;
  for (let i = 8; i < 256; i++) table[i] = table[i - 4]! ^ table[i - 5]! ^ table[i - 6]! ^ table[i - 8]!;
  return table;
}

function buildLogTable(exp: number[]): number[] {
  const table = new Array<number>(256).fill(0);
  for (let i = 0; i < 255; i++) table[exp[i]!] = i;
  return table;
}

function gexp(n: number): number {
  // Always in range: the modulo folds any input into 0..254.
  return EXP_TABLE[((n % 255) + 255) % 255]!;
}

function gmul(a: number, b: number): number {
  if (a === 0 || b === 0) return 0;
  // a and b are GF(256) elements (0..255), always in range for both tables.
  return gexp(LOG_TABLE[a]! + LOG_TABLE[b]!);
}

// ---- fixed tables (ISO/IEC 18004) ---------------------------------------------------------------------------------

/** Reed-Solomon blocks at error-correction level L, versions 1..20, each block as [totalCodewords, dataCodewords]. */
const RS_BLOCKS_L: [number, number][][] = [
  [[26, 19]],
  [[44, 34]],
  [[70, 55]],
  [[100, 80]],
  [[134, 108]],
  [
    [86, 68],
    [86, 68],
  ],
  [
    [98, 78],
    [98, 78],
  ],
  [
    [121, 97],
    [121, 97],
  ],
  [
    [146, 116],
    [146, 116],
  ],
  [
    [86, 68],
    [86, 68],
    [87, 69],
    [87, 69],
  ],
  [
    [101, 81],
    [101, 81],
    [101, 81],
    [101, 81],
  ],
  [
    [116, 92],
    [116, 92],
    [117, 93],
    [117, 93],
  ],
  [
    [133, 107],
    [133, 107],
    [133, 107],
    [133, 107],
  ],
  [
    [145, 115],
    [145, 115],
    [145, 115],
    [146, 116],
  ],
  [
    [109, 87],
    [109, 87],
    [109, 87],
    [109, 87],
    [109, 87],
    [110, 88],
  ],
  [
    [122, 98],
    [122, 98],
    [122, 98],
    [122, 98],
    [122, 98],
    [123, 99],
  ],
  [
    [135, 107],
    [136, 108],
    [136, 108],
    [136, 108],
    [136, 108],
    [136, 108],
  ],
  [
    [150, 120],
    [150, 120],
    [150, 120],
    [150, 120],
    [150, 120],
    [151, 121],
  ],
  [
    [141, 113],
    [141, 113],
    [141, 113],
    [142, 114],
    [142, 114],
    [142, 114],
    [142, 114],
  ],
  [
    [135, 107],
    [135, 107],
    [135, 107],
    [136, 108],
    [136, 108],
    [136, 108],
    [136, 108],
    [136, 108],
  ],
];

/** Centres of the alignment-pattern grid per version (versions 1..20); version 1 has none. */
const ALIGNMENT_POSITIONS: number[][] = [
  [],
  [6, 18],
  [6, 22],
  [6, 26],
  [6, 30],
  [6, 34],
  [6, 22, 38],
  [6, 24, 42],
  [6, 26, 46],
  [6, 28, 50],
  [6, 30, 54],
  [6, 32, 58],
  [6, 34, 62],
  [6, 26, 46, 66],
  [6, 26, 48, 70],
  [6, 26, 50, 74],
  [6, 30, 54, 78],
  [6, 30, 56, 82],
  [6, 30, 58, 86],
  [6, 34, 62, 90],
];

const G15 = (1 << 10) | (1 << 8) | (1 << 5) | (1 << 4) | (1 << 2) | (1 << 1) | (1 << 0);
const G15_MASK = (1 << 14) | (1 << 12) | (1 << 10) | (1 << 4) | (1 << 1);
const G18 = (1 << 12) | (1 << 11) | (1 << 10) | (1 << 9) | (1 << 8) | (1 << 5) | (1 << 2) | (1 << 0);
const PAD0 = 0xec;
const PAD1 = 0x11;
/** ISO/IEC 18004 §7.5 error-correction level codes; only L is used here. */
const ERROR_CORRECT_L = 1;
const MODE_8BIT_BYTE = 0b0100;

const MASK_FUNCS: ((row: number, col: number) => boolean)[] = [
  (row, col) => (row + col) % 2 === 0,
  (row) => row % 2 === 0,
  (_row, col) => col % 3 === 0,
  (row, col) => (row + col) % 3 === 0,
  (row, col) => (Math.floor(row / 2) + Math.floor(col / 3)) % 2 === 0,
  (row, col) => ((row * col) % 2) + ((row * col) % 3) === 0,
  (row, col) => (((row * col) % 2) + ((row * col) % 3)) % 2 === 0,
  (row, col) => (((row * col) % 3) + ((row + col) % 2)) % 2 === 0,
];

function bitLength(value: number): number {
  return value === 0 ? 0 : 32 - Math.clz32(value);
}

function bchTypeInfo(data: number): number {
  let d = data << 10;
  while (bitLength(d) - bitLength(G15) >= 0) d ^= G15 << (bitLength(d) - bitLength(G15));
  return ((data << 10) | d) ^ G15_MASK;
}

function bchTypeNumber(data: number): number {
  let d = data << 12;
  while (bitLength(d) - bitLength(G18) >= 0) d ^= G18 << (bitLength(d) - bitLength(G18));
  return (data << 12) | d;
}

// ---- bit buffer and byte-mode data codewords ------------------------------------------------------------------

class BitWriter {
  bytes: number[] = [];
  length = 0;

  put(value: number, bits: number): void {
    for (let i = bits - 1; i >= 0; i--) this.putBit(((value >> i) & 1) === 1);
  }

  putBit(bit: boolean): void {
    const byteIndex = Math.floor(this.length / 8);
    if (this.bytes.length <= byteIndex) this.bytes.push(0);
    // Just ensured the byte at byteIndex exists above.
    if (bit) this.bytes[byteIndex]! |= 0x80 >> this.length % 8;
    this.length++;
  }
}

function blocksFor(version: number): [number, number][] {
  // Caller always supplies a version in 1..RS_BLOCKS_L.length (bestVersion never returns outside that range).
  return RS_BLOCKS_L[version - 1]!;
}

function bitLimit(version: number): number {
  return 8 * blocksFor(version).reduce((sum, [, dataCount]) => sum + dataCount, 0);
}

/** The smallest version (1..20) whose byte-mode capacity at level L fits `byteLength` bytes. */
function bestVersion(byteLength: number): number {
  let sizeBits = 8; // versions 1..9 use an 8-bit character count; 10..20 use 16.
  for (let attempt = 0; attempt < 2; attempt++) {
    const needed = 4 + sizeBits + byteLength * 8;
    let found = -1;
    for (let version = 1; version <= RS_BLOCKS_L.length; version++) {
      if (bitLimit(version) >= needed) {
        found = version;
        break;
      }
    }
    if (found === -1) throw new Error(`QR data too long to encode: ${byteLength} bytes`);
    const nextSizeBits = found < 10 ? 8 : 16;
    if (nextSizeBits === sizeBits) return found;
    sizeBits = nextSizeBits;
  }
  throw new Error("unreachable");
}

function rsGenerator(ecCount: number): number[] {
  let poly = [1];
  for (let i = 0; i < ecCount; i++) poly = polyMul(poly, [1, gexp(i)]);
  return poly;
}

function polyMul(a: number[], b: number[]): number[] {
  const result = new Array<number>(a.length + b.length - 1).fill(0);
  for (let i = 0; i < a.length; i++) for (let j = 0; j < b.length; j++) result[i + j] = result[i + j]! ^ gmul(a[i]!, b[j]!);
  return result;
}

/** The `ecCount` Reed-Solomon error-correction codewords for one data block. */
function reedSolomonEncode(data: number[], ecCount: number): number[] {
  const generator = rsGenerator(ecCount);
  const buffer = data.concat(new Array<number>(ecCount).fill(0));
  for (let i = 0; i < data.length; i++) {
    const coefficient = buffer[i]!;
    if (coefficient !== 0) for (let j = 0; j < generator.length; j++) buffer[i + j] = buffer[i + j]! ^ gmul(generator[j]!, coefficient);
  }
  return buffer.slice(data.length);
}

/** Mode indicator, length, byte data, terminator and padding, then interleaved data and EC codewords, ready to place. */
function createData(version: number, bytes: Uint8Array): number[] {
  const blocks = blocksFor(version);
  const sizeBits = version < 10 ? 8 : 16;
  const writer = new BitWriter();
  writer.put(MODE_8BIT_BYTE, 4);
  writer.put(bytes.length, sizeBits);
  for (const value of bytes) writer.put(value, 8);

  const limit = bitLimit(version);
  if (writer.length > limit) throw new Error(`QR data too long to encode: ${bytes.length} bytes`);
  for (let i = 0; i < Math.min(limit - writer.length, 4); i++) writer.putBit(false);
  const delimit = writer.length % 8;
  if (delimit) for (let i = 0; i < 8 - delimit; i++) writer.putBit(false);
  const fillBytes = (limit - writer.length) / 8;
  for (let i = 0; i < fillBytes; i++) writer.put(i % 2 === 0 ? PAD0 : PAD1, 8);

  let offset = 0;
  const dataBlocks: number[][] = [];
  const ecBlocks: number[][] = [];
  let maxData = 0;
  let maxEc = 0;
  for (const [total, dataCount] of blocks) {
    const dataBytes = writer.bytes.slice(offset, offset + dataCount);
    offset += dataCount;
    const ecBytes = reedSolomonEncode(dataBytes, total - dataCount);
    dataBlocks.push(dataBytes);
    ecBlocks.push(ecBytes);
    maxData = Math.max(maxData, dataBytes.length);
    maxEc = Math.max(maxEc, ecBytes.length);
  }
  const codewords: number[] = [];
  for (let i = 0; i < maxData; i++) for (const block of dataBlocks) if (i < block.length) codewords.push(block[i]!);
  for (let i = 0; i < maxEc; i++) for (const block of ecBlocks) if (i < block.length) codewords.push(block[i]!);
  return codewords;
}

// ---- matrix construction ---------------------------------------------------------------------------------------

type Cell = boolean | null;

/** A square grid whose every index in 0..size-1 is always populated (rows are pre-filled, never sparse). */
class Grid {
  private readonly rows: Cell[][];

  constructor(readonly size: number) {
    this.rows = Array.from({ length: size }, () => new Array<Cell>(size).fill(null));
  }

  get(row: number, col: number): Cell {
    return this.rows[row]![col]!;
  }

  set(row: number, col: number, value: boolean): void {
    this.rows[row]![col] = value;
  }

  row(index: number): Cell[] {
    return this.rows[index]!;
  }

  column(index: number): Cell[] {
    return this.rows.map((line) => line[index]!);
  }
}

function setupFinder(modules: Grid, size: number, row: number, col: number): void {
  for (let r = -1; r <= 7; r++) {
    if (row + r <= -1 || size <= row + r) continue;
    for (let c = -1; c <= 7; c++) {
      if (col + c <= -1 || size <= col + c) continue;
      const dark = (r >= 0 && r <= 6 && (c === 0 || c === 6)) || (c >= 0 && c <= 6 && (r === 0 || r === 6)) || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
      modules.set(row + r, col + c, dark);
    }
  }
}

function setupAlignment(modules: Grid, version: number): void {
  const positions = ALIGNMENT_POSITIONS[version - 1]!;
  for (const row of positions) {
    for (const col of positions) {
      if (modules.get(row, col) !== null) continue;
      for (let r = -2; r <= 2; r++) {
        for (let c = -2; c <= 2; c++) {
          const dark = r === -2 || r === 2 || c === -2 || c === 2 || (r === 0 && c === 0);
          modules.set(row + r, col + c, dark);
        }
      }
    }
  }
}

function setupTiming(modules: Grid, size: number): void {
  for (let r = 8; r < size - 8; r++) if (modules.get(r, 6) === null) modules.set(r, 6, r % 2 === 0);
  for (let c = 8; c < size - 8; c++) if (modules.get(6, c) === null) modules.set(6, c, c % 2 === 0);
}

function setupTypeInfo(modules: Grid, size: number, test: boolean, maskPattern: number): void {
  const bits = bchTypeInfo((ERROR_CORRECT_L << 3) | maskPattern);
  for (let i = 0; i < 15; i++) {
    const mod = !test && ((bits >> i) & 1) === 1;
    if (i < 6) modules.set(i, 8, mod);
    else if (i < 8) modules.set(i + 1, 8, mod);
    else modules.set(size - 15 + i, 8, mod);
  }
  for (let i = 0; i < 15; i++) {
    const mod = !test && ((bits >> i) & 1) === 1;
    if (i < 8) modules.set(8, size - i - 1, mod);
    else if (i < 9) modules.set(8, 15 - i - 1 + 1, mod);
    else modules.set(8, 15 - i - 1, mod);
  }
  modules.set(size - 8, 8, !test);
}

function setupTypeNumber(modules: Grid, size: number, test: boolean, version: number): void {
  const bits = bchTypeNumber(version);
  for (let i = 0; i < 18; i++) {
    const mod = !test && ((bits >> i) & 1) === 1;
    modules.set(Math.floor(i / 3), (i % 3) + size - 8 - 3, mod);
    modules.set((i % 3) + size - 8 - 3, Math.floor(i / 3), mod);
  }
}

function mapData(modules: Grid, size: number, data: number[], maskPattern: number): void {
  const maskFn = MASK_FUNCS[maskPattern]!;
  let inc = -1;
  let row = size - 1;
  let bitIndex = 7;
  let byteIndex = 0;
  for (let baseCol = size - 1; baseCol > 0; baseCol -= 2) {
    // `baseCol` must stay the clean 20,18,16,…,2 progression; only `col` (used below) gets the col-6 adjustment,
    // matching a Python `for col in range(...)` loop where reassigning `col` in the body never affects the next
    // iteration's value from the range object (a plain JS `for` would otherwise let this adjustment compound).
    const col = baseCol <= 6 ? baseCol - 1 : baseCol;
    const colRange = [col, col - 1];
    for (;;) {
      for (const c of colRange) {
        if (modules.get(row, c) === null) {
          let dark = byteIndex < data.length && ((data[byteIndex]! >> bitIndex) & 1) === 1;
          if (maskFn(row, c)) dark = !dark;
          modules.set(row, c, dark);
          bitIndex--;
          if (bitIndex === -1) {
            byteIndex++;
            bitIndex = 7;
          }
        }
      }
      row += inc;
      if (row < 0 || size <= row) {
        row -= inc;
        inc = -inc;
        break;
      }
    }
  }
}

function buildMatrix(version: number, maskPattern: number, test: boolean, data: number[]): Grid {
  const size = version * 4 + 17;
  const modules = new Grid(size);
  setupFinder(modules, size, 0, 0);
  setupFinder(modules, size, size - 7, 0);
  setupFinder(modules, size, 0, size - 7);
  setupAlignment(modules, version);
  setupTiming(modules, size);
  setupTypeInfo(modules, size, test, maskPattern);
  if (version >= 7) setupTypeNumber(modules, size, test, version);
  mapData(modules, size, data, maskPattern);
  return modules;
}

/** `buildMatrix` leaves no cell null (every position is either a function pattern or a placed data/remainder bit). */
function toBooleanRows(modules: Grid, size: number): boolean[][] {
  return Array.from({ length: size }, (_, row) => Array.from({ length: size }, (_, col) => modules.get(row, col) === true));
}

// ---- mask penalty (ISO/IEC 18004 §7.8.3), to pick the most legible of the 8 masks -------------------------------

function lostPoint(modules: boolean[][], size: number): number {
  return lostPointRuns(modules, size) + lostPointBlocks(modules, size) + lostPointFinderLike(modules, size) + lostPointBalance(modules, size);
}

function lostPointRuns(modules: boolean[][], size: number): number {
  let points = 0;
  for (let row = 0; row < size; row++) points += runPenalty(modules[row]!);
  for (let col = 0; col < size; col++) points += runPenalty(modules.map((line) => line[col]!));
  return points;
}

function runPenalty(line: boolean[]): number {
  let points = 0;
  let previous = line[0];
  let length = 0;
  for (const value of line) {
    if (value === previous) length++;
    else {
      if (length >= 5) points += length - 2;
      length = 1;
      previous = value;
    }
  }
  if (length >= 5) points += length - 2;
  return points;
}

function lostPointBlocks(modules: boolean[][], size: number): number {
  let points = 0;
  for (let row = 0; row < size - 1; row++) {
    for (let col = 0; col < size - 1; col++) {
      const corner = modules[row]![col];
      if (modules[row]![col + 1] === corner && modules[row + 1]![col] === corner && modules[row + 1]![col + 1] === corner) points += 3;
    }
  }
  return points;
}

/** 1:1:3:1:1 dark/light ratio (the finder-pattern silhouette), 4 light modules wide, either side: "10111010000" or "00001011101". */
const FINDER_LIKE_PATTERN_A = [true, false, true, true, true, false, true, false, false, false, false];
const FINDER_LIKE_PATTERN_B = [false, false, false, false, true, false, true, true, true, false, true];

function lostPointFinderLike(modules: boolean[][], size: number): number {
  let points = 0;
  for (let row = 0; row < size; row++) {
    for (let col = 0; col <= size - FINDER_LIKE_PATTERN_A.length; col++) {
      if (windowMatches(modules[row]!, col, FINDER_LIKE_PATTERN_A) || windowMatches(modules[row]!, col, FINDER_LIKE_PATTERN_B)) points += 40;
    }
  }
  for (let col = 0; col < size; col++) {
    const column = modules.map((line) => line[col]!);
    for (let row = 0; row <= size - FINDER_LIKE_PATTERN_A.length; row++) {
      if (windowMatches(column, row, FINDER_LIKE_PATTERN_A) || windowMatches(column, row, FINDER_LIKE_PATTERN_B)) points += 40;
    }
  }
  return points;
}

function windowMatches(line: boolean[], start: number, pattern: boolean[]): boolean {
  for (let i = 0; i < pattern.length; i++) if (line[start + i] !== pattern[i]) return false;
  return true;
}

function lostPointBalance(modules: boolean[][], size: number): number {
  let dark = 0;
  for (let row = 0; row < size; row++) for (let col = 0; col < size; col++) if (modules[row]![col]) dark++;
  const percent = (dark / (size * size)) * 100;
  return Math.floor(Math.abs(percent - 50) / 5) * 10;
}

// ---- public API -----------------------------------------------------------------------------------------------

/** Encodes `text` as a QR Code (byte mode, error-correction level L) and returns its square module matrix, no quiet zone. */
export function encodeQrMatrix(text: string): boolean[][] {
  const bytes = new TextEncoder().encode(text);
  const version = bestVersion(bytes.length);
  const data = createData(version, bytes);
  let bestMask = 0;
  let bestScore = Infinity;
  const size = version * 4 + 17;
  for (let mask = 0; mask < 8; mask++) {
    const trial = toBooleanRows(buildMatrix(version, mask, true, data), size);
    const score = lostPoint(trial, size);
    if (score < bestScore) {
      bestScore = score;
      bestMask = mask;
    }
  }
  return toBooleanRows(buildMatrix(version, bestMask, false, data), size);
}
