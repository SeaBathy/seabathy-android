// Decoding is ported from Esri's LERC (Limited Error Raster Compression): the Lerc2 decoder of
// LercDecode.js 3.0.0, with values worked out as src/LercLib does. Copyright 2015-2021 Esri,
// Apache License 2.0; see licenses.txt (in the app's assets) and http://github.com/Esri/lerc/.
package com.seabathy.bathypicker.core;

import java.io.IOException;

/**
 * Reads one LERC 2 blob, as a GeoTIFF with compression 34887 stores each tile (versions 2 to 5,
 * one value per pixel). Values come out as floats; pixels the blob marks invalid are NaN, as
 * GDAL gives them for float data.
 */
final class Lerc2 {
    private Lerc2() {}

    /** Decodes the blob at data[off, off + len) into width * height floats, row by row. */
    static float[] decode(byte[] data, int off, int len, int width, int height) throws IOException {
        Reader r = new Reader(data, off, len);
        byte[] id = r.bytes(6);
        if (!new String(id, java.nio.charset.StandardCharsets.US_ASCII).equals("Lerc2 "))
            throw new IOException("Not a LERC 2 tile.");
        int version = r.i32();
        if (version < 2 || version > 5) throw new RemoteTiff.UnsupportedException("LERC 2." + version + " tiles aren't supported.");
        long checksum = version >= 3 ? r.u32() : -1;
        int rows = r.i32(), cols = r.i32();
        int dims = version >= 4 ? r.i32() : 1;
        int numValid = r.i32(), microBlock = r.i32(), blobSize = r.i32(), type = r.i32();
        double maxZError = r.f64(), zMin = r.f64(), zMax = r.f64();
        if (rows != height || cols != width) throw new IOException("A LERC tile is " + cols + " x " + rows + ", not " + width + " x " + height + ".");
        if (dims != 1) throw new RemoteTiff.UnsupportedException("LERC tiles with " + dims + " values per pixel aren't supported.");
        if (type < 0 || type > 7) throw new IOException("A LERC tile has an unknown data type.");
        if (blobSize < 0 || blobSize > len) throw new IOException("A LERC tile is cut short.");
        if (version >= 3 && blobSize >= 14 && fletcher32(data, off + 14, blobSize - 14) != checksum)
            throw new IOException("A LERC tile is damaged (checksum).");

        int n = width * height;
        float[] out = new float[n];
        boolean[] valid = mask(r, n, numValid);
        if (numValid == 0) {
            java.util.Arrays.fill(out, Float.NaN);
            return out;
        }
        if (zMin == zMax) {
            fill(out, valid, (float) zMax);
            return finish(out, valid);
        }
        if (version >= 4) {
            double lo = r.value(type), hi = r.value(type);
            if (lo == hi) {
                fill(out, valid, (float) hi);
                return finish(out, valid);
            }
        }
        boolean oneSweep = r.u8() != 0;
        if (oneSweep) {
            for (int k = 0; k < n; k++) if (valid == null || valid[k]) out[k] = (float) r.value(type);
            return finish(out, valid);
        }
        if (type <= 1 && Math.abs(maxZError - 0.5) < 0.00001) {
            int huffman = r.u8();
            if (huffman != 0) throw new RemoteTiff.UnsupportedException("LERC tiles packed with Huffman codes (8-bit data) aren't supported.");
        }
        tiles(r, out, valid, width, height, microBlock, type, maxZError, zMax, version);
        return finish(out, valid);
    }

    /** The micro blocks: each constant, raw, or offset plus bit-stuffed steps of 2 * maxZError. */
    private static void tiles(Reader r, float[] out, boolean[] valid, int width, int height, int mb, int type,
                              double maxZError, double zMax, int version) throws IOException {
        if (mb <= 0) throw new IOException("A LERC tile has no block size.");
        int nbx = (width + mb - 1) / mb, nby = (height + mb - 1) / mb;
        int check = version >= 5 ? 14 : 15;
        long[] buf = new long[mb * mb];
        double scale = 2 * maxZError;
        for (int by = 0; by < nby; by++) {
            int i0 = by * mb, i1 = Math.min(height, i0 + mb);
            for (int bx = 0; bx < nbx; bx++) {
                int j0 = bx * mb, j1 = Math.min(width, j0 + mb);
                int head = r.u8();
                if (version >= 5 && (head & 4) != 0) throw new IOException("A LERC tile is damaged (block encoding).");
                int test = (head >> 2) & check;
                if (test != (((bx * mb) >> 3) & check)) throw new IOException("A LERC tile is damaged (block check).");
                int enc = head & 3, bits67 = head >> 6;
                if (enc == 2) {                               // constant 0
                    for (int i = i0; i < i1; i++)
                        for (int j = j0; j < j1; j++) if (valid == null || valid[i * width + j]) out[i * width + j] = 0f;
                } else if (enc == 0) {                        // raw values of the valid pixels
                    for (int i = i0; i < i1; i++)
                        for (int j = j0; j < j1; j++) if (valid == null || valid[i * width + j]) out[i * width + j] = (float) r.value(type);
                } else {
                    double offset = r.value(typeUsed(type, bits67));
                    if (enc == 3) {                           // constant offset
                        float v = (float) offset;
                        for (int i = i0; i < i1; i++)
                            for (int j = j0; j < j1; j++) if (valid == null || valid[i * width + j]) out[i * width + j] = v;
                    } else {
                        int count = unstuffBlock(r, buf, version);
                        int c = 0;
                        for (int i = i0; i < i1; i++) {
                            for (int j = j0; j < j1; j++) {
                                int k = i * width + j;
                                if (valid != null && !valid[k]) continue;
                                if (c >= count) throw new IOException("A LERC tile is damaged (too few values).");
                                out[k] = (float) Math.min(offset + buf[c++] * scale, zMax);
                            }
                        }
                    }
                }
            }
        }
    }

    /** BitStuffer2::Decode: a block's numbers, optionally through a look-up table. Returns how many. */
    private static int unstuffBlock(Reader r, long[] dest, int version) throws IOException {
        int head = r.u8();
        int bits67 = head >> 6;
        int nb = bits67 == 0 ? 4 : 3 - bits67;
        if (nb != 1 && nb != 2 && nb != 4) throw new IOException("A LERC tile is damaged (count size).");
        boolean lut = (head & 32) != 0;
        int numBits = head & 31;
        long count = nb == 1 ? r.u8() : nb == 2 ? r.u16() : r.u32();
        if (count > dest.length) throw new IOException("A LERC tile is damaged (block count).");
        int n = (int) count;
        if (!lut) {
            if (numBits == 0) {
                java.util.Arrays.fill(dest, 0, n, 0L);
            } else {
                unstuff(r, dest, n, numBits, version);
            }
            return n;
        }
        int nLut = r.u8() - 1;
        if (nLut < 0) throw new IOException("A LERC tile is damaged (table).");
        long[] table = new long[nLut + 1];
        long[] tmp = new long[Math.max(1, nLut)];
        if (nLut > 0) unstuff(r, tmp, nLut, numBits, version);
        System.arraycopy(tmp, 0, table, 1, nLut);
        int indexBits = 0;
        while ((nLut >> indexBits) != 0) indexBits++;
        if (indexBits == 0) {
            java.util.Arrays.fill(dest, 0, n, 0L);
        } else {
            unstuff(r, dest, n, indexBits, version);
        }
        for (int i = 0; i < n; i++) {
            if (dest[i] < 0 || dest[i] > nLut) throw new IOException("A LERC tile is damaged (table index).");
            dest[i] = table[(int) dest[i]];
        }
        return n;
    }

    /**
     * n numbers of numBits each from ceil(n * numBits / 8) bytes, read as little-endian 32-bit
     * words: lowest bits first since LERC 2.3 (version 3), highest bits first before that.
     */
    private static void unstuff(Reader r, long[] dest, int n, int numBits, int version) throws IOException {
        long totalBits = (long) n * numBits;
        int numBytes = (int) ((totalBits + 7) / 8);
        int numWords = (int) ((totalBits + 31) / 32);
        byte[] raw = r.bytes(numBytes);
        long[] w = new long[numWords];
        for (int i = 0; i < numBytes; i++) w[i >> 2] |= (raw[i] & 0xffL) << (8 * (i & 3));
        long mask = (1L << numBits) - 1;
        if (version >= 3) {
            long acc = 0;
            int have = 0, wi = 0;
            for (int i = 0; i < n; i++) {
                if (have < numBits) {
                    acc |= w[wi++] << have;
                    have += 32;
                }
                dest[i] = acc & mask;
                acc >>>= numBits;
                have -= numBits;
            }
        } else {
            // The last word's unused tail bytes were left out: shift what's there to the top.
            int tail = numWords * 4 - numBytes;
            if (numWords > 0) w[numWords - 1] = (w[numWords - 1] << (8 * tail)) & 0xffffffffL;
            long acc = 0;
            int have = 0, wi = 0;
            for (int i = 0; i < n; i++) {
                if (have < numBits) {
                    acc = (acc << 32) | w[wi++];
                    have += 32;
                }
                dest[i] = (acc >>> (have - numBits)) & mask;
                have -= numBits;
                acc &= have == 0 ? 0 : (1L << have) - 1;
            }
        }
    }

    /** The valid-pixel mask: null when every pixel is valid. */
    private static boolean[] mask(Reader r, int n, int numValid) throws IOException {
        int numBytes = r.i32();
        if (numValid == 0) {
            r.skip(numBytes);
            return new boolean[n];
        }
        if (numValid == n) {
            if (numBytes != 0) throw new IOException("A LERC tile is damaged (mask).");
            return null;
        }
        if (numBytes <= 0) throw new IOException("A LERC tile has no mask for its empty pixels.");
        int end = r.pos + numBytes;
        byte[] bits = new byte[(n + 7) / 8];
        int op = 0;
        short cnt = (short) r.u16();
        while (cnt != Short.MIN_VALUE) {
            if (r.pos > end) throw new IOException("A LERC tile is damaged (mask run).");
            if (cnt > 0) {
                for (int i = 0; i < cnt; i++) {
                    int b = r.u8();
                    if (op < bits.length) bits[op] = (byte) b;
                    op++;
                }
            } else {
                int b = r.u8();
                for (int i = 0; i < -cnt; i++) {
                    if (op < bits.length) bits[op] = (byte) b;
                    op++;
                }
            }
            cnt = (short) r.u16();
        }
        if (op < bits.length) throw new IOException("A LERC tile is damaged (mask too short).");
        r.pos = end;
        boolean[] valid = new boolean[n];
        for (int k = 0; k < n; k++) valid[k] = (bits[k >> 3] & (0x80 >> (k & 7))) != 0;
        return valid;
    }

    private static void fill(float[] out, boolean[] valid, float v) {
        for (int k = 0; k < out.length; k++) if (valid == null || valid[k]) out[k] = v;
    }

    private static float[] finish(float[] out, boolean[] valid) {
        if (valid != null) for (int k = 0; k < out.length; k++) if (!valid[k]) out[k] = Float.NaN;
        return out;
    }

    /** The type a block's offset is stored in: smaller than the data's own when it fits (Lerc2::GetDataTypeUsed). */
    static int typeUsed(int type, int tc) {
        switch (type) {
            case 2: case 4: return type - tc;
            case 3: case 5: return type - 2 * tc;
            case 6: return tc == 0 ? 6 : (tc == 1 ? 2 : 1);
            case 7: return tc == 0 ? 7 : type - 2 * tc + 1;
            default: return type;
        }
    }

    /** Fletcher-32 over the blob after its checksum field, as Lerc2::ComputeChecksumFletcher32. */
    static long fletcher32(byte[] b, int off, int len) {
        long sum1 = 0xffff, sum2 = 0xffff;
        int words = len / 2, p = off;
        while (words > 0) {
            int t = Math.min(words, 359);
            words -= t;
            do {
                sum1 += (b[p++] & 0xff) << 8;
                sum1 += b[p++] & 0xff;
                sum2 += sum1;
            } while (--t > 0);
            sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
            sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        }
        if ((len & 1) != 0) {
            sum1 += (b[p] & 0xff) << 8;
            sum2 += sum1;
        }
        sum1 = (sum1 & 0xffff) + (sum1 >>> 16);
        sum2 = (sum2 & 0xffff) + (sum2 >>> 16);
        return ((sum2 << 16) | sum1) & 0xffffffffL;
    }

    /** Little-endian reads with bounds checks (a short tile throws instead of reading past the end). */
    private static final class Reader {
        final byte[] b;
        final int end;
        int pos;

        Reader(byte[] b, int off, int len) {
            this.b = b;
            this.pos = off;
            this.end = off + len;
        }

        void need(int n) throws IOException {
            if (n < 0 || pos + n > end) throw new IOException("A LERC tile is cut short.");
        }

        void skip(int n) throws IOException {
            need(n);
            pos += n;
        }

        byte[] bytes(int n) throws IOException {
            need(n);
            byte[] o = java.util.Arrays.copyOfRange(b, pos, pos + n);
            pos += n;
            return o;
        }

        int u8() throws IOException {
            need(1);
            return b[pos++] & 0xff;
        }

        int u16() throws IOException {
            need(2);
            int v = (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8);
            pos += 2;
            return v;
        }

        int i32() throws IOException {
            need(4);
            int v = (b[pos] & 0xff) | ((b[pos + 1] & 0xff) << 8) | ((b[pos + 2] & 0xff) << 16) | ((b[pos + 3] & 0xff) << 24);
            pos += 4;
            return v;
        }

        long u32() throws IOException {
            return i32() & 0xffffffffL;
        }

        double f64() throws IOException {
            long lo = u32(), hi = u32();
            return Double.longBitsToDouble((hi << 32) | lo);
        }

        /** One value of a LERC data type (0 char, 1 byte, 2 short, 3 ushort, 4 int, 5 uint, 6 float, 7 double). */
        double value(int type) throws IOException {
            switch (type) {
                case 0: need(1); return b[pos++];
                case 1: return u8();
                case 2: return (short) u16();
                case 3: return u16();
                case 4: return i32();
                case 5: return u32();
                case 6: return Float.intBitsToFloat(i32());
                case 7: return f64();
                default: throw new IOException("A LERC tile has an unknown data type.");
            }
        }
    }
}
