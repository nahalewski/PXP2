package org.pxp2.core;

/**
 * Original interpreter for the Allegrex VFPU (vector coprocessor 2).
 * Registers are kept as raw IEEE-754 bit patterns so integer-typed VFPU data survives unchanged.
 * Register addressing: index = matrix*4 + column + row*32 (128 single-precision registers).
 * Unsupported encodings stop execution instead of guessing.
 */
public final class Vfpu {
    public static final int SPFX = 0, TPFX = 1, DPFX = 2, CC = 3, REV = 7, RCX0 = 8;
    public final int[] v = new int[128];
    public final int[] ctrl = new int[16];
    private int random = 0x3f800001;
    private static final float[] PREFIX_CONSTANTS = {0f, 1f, 2f, 0.5f, 3f, 1f/3f, 0.25f, 1f/6f};
    private static final float[] VCST = {
        0f, Float.MAX_VALUE, (float)Math.sqrt(2), (float)Math.sqrt(0.5), (float)(2/Math.sqrt(Math.PI)), (float)(2/Math.PI),
        (float)(1/Math.PI), (float)(Math.PI/4), (float)(Math.PI/2), (float)Math.PI, (float)Math.E, (float)(1/Math.log(2)),
        (float)Math.log10(Math.E), (float)Math.log(2), (float)Math.log(10), (float)(2*Math.PI), (float)(Math.PI/6),
        (float)Math.log10(2), (float)(Math.log(10)/Math.log(2)), (float)(Math.sqrt(3)/2), 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};

    public Vfpu() { reset(); }
    public void reset() {
        java.util.Arrays.fill(v, 0); java.util.Arrays.fill(ctrl, 0);
        ctrl[SPFX] = 0xe4; ctrl[TPFX] = 0xe4; ctrl[CC] = 0x3f; ctrl[REV] = 0x7772ceab;
        ctrl[8] = 0x3f800001; ctrl[9] = 0x3f800002; ctrl[10] = 0x3f800004; ctrl[11] = 0x3f800008;
        for (int i = 12; i < 16; i++) ctrl[i] = 0x3f800000;
    }
    public void copyFrom(Vfpu other) { System.arraycopy(other.v, 0, v, 0, 128); System.arraycopy(other.ctrl, 0, ctrl, 0, 16); }

    float f(int reg) { return Float.intBitsToFloat(v[reg]); }
    public int cc(int bit) { return (ctrl[CC] >>> bit) & 1; }

    // ---- register addressing -------------------------------------------------------------
    static int size(int op) { return (((op >>> 7) & 1) | ((op >>> 14) & 2)) + 1; }
    /** Register indexes of an n-element vector; always returns 4 entries so prefix swizzles can wrap. */
    static int[] vector(int reg, int n) {
        int mtx = (reg >>> 2) & 7, col = reg & 3, row, transpose = (reg >>> 5) & 1;
        switch (n) {
            case 1: transpose = 0; row = (reg >>> 5) & 3; break;
            case 3: row = (reg >>> 6) & 1; break;
            default: row = (reg >>> 5) & 2; break;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) out[i] = mtx * 4 + (transpose != 0 ? ((row + i) & 3) + col * 32 : col + ((row + i) & 3) * 32);
        return out;
    }
    /** Matrix register indexes; entry [j*4+i] is row i, column j (column-major as addressed by the VFPU). */
    static int[] matrix(int reg, int side) {
        int mtx = (reg >>> 2) & 7, col = reg & 3, row, transpose = (reg >>> 5) & 1;
        switch (side) {
            case 1: transpose = 0; row = (reg >>> 5) & 3; break;
            case 3: row = (reg >>> 6) & 1; break;
            default: row = (reg >>> 5) & 2; break;
        }
        int[] out = new int[16];
        for (int i = 0; i < side; i++) for (int j = 0; j < side; j++)
            out[j * 4 + i] = mtx * 4 + (transpose != 0 ? ((row + i) & 3) + ((col + j) & 3) * 32 : ((col + j) & 3) + ((row + i) & 3) * 32);
        return out;
    }

    // ---- prefixed reads and writes ---------------------------------------------------------
    private float[] read(int reg, int n, int prefix) {
        int[] regs = vector(reg, n); float[] raw = new float[4], out = new float[4];
        for (int i = 0; i < 4; i++) raw[i] = f(regs[i]);
        for (int i = 0; i < n; i++) {
            int swz = (prefix >>> (i * 2)) & 3, abs = (prefix >>> (8 + i)) & 1, cst = (prefix >>> (12 + i)) & 1, neg = (prefix >>> (16 + i)) & 1;
            float x = cst != 0 ? PREFIX_CONSTANTS[swz + (abs << 2)] : (abs != 0 ? Math.abs(raw[swz]) : raw[swz]);
            out[i] = neg != 0 ? -x : x;
        }
        return out;
    }
    private float[] readS(int op, int n) { return read((op >>> 8) & 0x7f, n, ctrl[SPFX]); }
    private float[] readT(int op, int n) { return read((op >>> 16) & 0x7f, n, ctrl[TPFX]); }
    private int[] readRaw(int reg, int n) { int[] regs = vector(reg, n), out = new int[4]; for (int i = 0; i < n; i++) out[i] = v[regs[i]]; return out; }
    private void write(int reg, int n, float[] d) {
        int[] regs = vector(reg, n); int p = ctrl[DPFX];
        for (int i = 0; i < n; i++) {
            if (((p >>> (8 + i)) & 1) != 0) continue;
            float x = d[i];
            switch ((p >>> (i * 2)) & 3) {
                case 1: x = Float.isNaN(x) ? x : Math.max(0f, Math.min(1f, x)); break;
                case 3: x = Float.isNaN(x) ? x : Math.max(-1f, Math.min(1f, x)); break;
                default: break;
            }
            v[regs[i]] = Float.floatToRawIntBits(x);
        }
    }
    private void writeRaw(int reg, int n, int[] d) {
        int[] regs = vector(reg, n); int p = ctrl[DPFX];
        for (int i = 0; i < n; i++) if (((p >>> (8 + i)) & 1) == 0) v[regs[i]] = d[i];
    }
    private void eat() { ctrl[SPFX] = 0xe4; ctrl[TPFX] = 0xe4; ctrl[DPFX] = 0; }
    private float[] readMatrix(int reg, int side) { int[] m = matrix(reg, side); float[] out = new float[16]; for (int i = 0; i < side; i++) for (int j = 0; j < side; j++) out[j*4+i] = f(m[j*4+i]); return out; }
    private void writeMatrix(int reg, int side, float[] d) { int[] m = matrix(reg, side); for (int i = 0; i < side; i++) for (int j = 0; j < side; j++) v[m[j*4+i]] = Float.floatToRawIntBits(d[j*4+i]); }

    static IllegalStateException unsupported(int op, int pc) {
        return new IllegalStateException(String.format("Unsupported VFPU instruction %08x at %08x", op, pc));
    }

    // ---- loads, stores, moves ------------------------------------------------------------
    public void load(Memory m, int op, int address, int pc) {
        switch (op >>> 26) {
            case 50: v[((op >>> 16) & 31) | ((op & 3) << 5)] = m.read32(address); break;              // lv.s
            case 54: { int[] r = vector(((op >>> 16) & 31) | ((op & 1) << 5), 4); for (int i = 0; i < 4; i++) v[r[i]] = m.read32(address + i * 4); break; } // lv.q
            case 53: { // lvl.q / lvr.q
                int[] r = vector(((op >>> 16) & 31) | ((op & 1) << 5), 4); int offset = (address >>> 2) & 3;
                if ((op & 2) == 0) for (int i = 0; i <= offset; i++) v[r[3 - i]] = m.read32(address - 4 * i);
                else for (int i = 0; i <= 3 - offset; i++) v[r[i]] = m.read32(address + 4 * i);
                break;
            }
            default: throw unsupported(op, pc);
        }
    }
    public void store(Memory m, int op, int address, int pc) {
        switch (op >>> 26) {
            case 58: m.write32(address, v[((op >>> 16) & 31) | ((op & 3) << 5)]); break;              // sv.s
            case 62: { int[] r = vector(((op >>> 16) & 31) | ((op & 1) << 5), 4); for (int i = 0; i < 4; i++) m.write32(address + i * 4, v[r[i]]); break; } // sv.q
            case 61: { // svl.q / svr.q
                int[] r = vector(((op >>> 16) & 31) | ((op & 1) << 5), 4); int offset = (address >>> 2) & 3;
                if ((op & 2) == 0) for (int i = 0; i <= offset; i++) m.write32(address - 4 * i, v[r[3 - i]]);
                else for (int i = 0; i <= 3 - offset; i++) m.write32(address + 4 * i, v[r[i]]);
                break;
            }
            default: throw unsupported(op, pc);
        }
    }
    /** mfv/mfvc: returns the raw register or control value. */
    public int moveFrom(int op) { int reg = op & 0xff; return reg < 128 ? v[reg] : ctrl[(reg - 128) & 15]; }
    /** mtv/mtvc. */
    public void moveTo(int op, int value) {
        int reg = op & 0xff;
        if (reg < 128) v[reg] = value;
        else { int c = (reg - 128) & 15; if (c == CC) value &= 0x3f; if (c <= DPFX) value &= 0xfffff; if (c != REV) ctrl[c] = value; }
    }

    // ---- arithmetic ----------------------------------------------------------------------
    public void execute(int op, int pc) {
        switch (op >>> 26) {
            case 24: vfpu0(op, pc); break;
            case 25: vfpu1(op, pc); break;
            case 27: vfpu3(op, pc); break;
            case 52: vfpu4(op, pc); break;
            case 55: vfpu5(op, pc); return; // prefix instructions do not consume prefixes
            case 60: vfpu6(op, pc); break;
            case 63: if (op == 0xffff0000 || op == 0xffff0320 || op == 0xffff040d) return; throw unsupported(op, pc); // vnop, vsync, vflush
            default: throw unsupported(op, pc);
        }
        eat();
    }
    private void vfpu0(int op, int pc) {
        int n = size(op); float[] s = readS(op, n), t = readT(op, n), d = new float[4];
        switch ((op >>> 23) & 7) {
            case 0: for (int i = 0; i < n; i++) d[i] = s[i] + t[i]; break;
            case 1: for (int i = 0; i < n; i++) d[i] = s[i] - t[i]; break;
            case 2: { int[] ti = readRaw((op >>> 16) & 0x7f, n); for (int i = 0; i < n; i++) d[i] = Math.scalb(s[i], ti[i]); break; } // vsbn
            case 7: for (int i = 0; i < n; i++) d[i] = s[i] / t[i]; break;
            default: throw unsupported(op, pc);
        }
        write(op & 0x7f, n, d);
    }
    private void vfpu1(int op, int pc) {
        int n = size(op); float[] s = readS(op, n), t = readT(op, n), d = new float[4];
        switch ((op >>> 23) & 7) {
            case 0: for (int i = 0; i < n; i++) d[i] = s[i] * t[i]; write(op & 0x7f, n, d); break;
            case 1: { float sum = 0; for (int i = 0; i < n; i++) sum += s[i] * t[i]; d[0] = sum; write(op & 0x7f, 1, d); break; } // vdot
            case 2: { float k = read((op >>> 16) & 0x7f, 1, ctrl[TPFX])[0]; for (int i = 0; i < n; i++) d[i] = s[i] * k; write(op & 0x7f, n, d); break; } // vscl
            case 4: { float sum = 0; for (int i = 0; i < n - 1; i++) sum += s[i] * t[i]; d[0] = sum + t[n - 1]; write(op & 0x7f, 1, d); break; } // vhdp
            case 5: if (n != 3) throw unsupported(op, pc); d[0] = s[1] * t[2]; d[1] = s[2] * t[0]; d[2] = s[0] * t[1]; write(op & 0x7f, 3, d); break; // vcrs.t
            case 6: if (n != 2) throw unsupported(op, pc); d[0] = s[0] * t[1] - s[1] * t[0]; write(op & 0x7f, 1, d); break; // vdet
            default: throw unsupported(op, pc);
        }
    }
    private static boolean compare(int cond, float s, float t) {
        switch (cond) {
            case 0: return false; case 1: return s == t; case 2: return s < t; case 3: return s <= t;
            case 4: return true; case 5: return s != t; case 6: return s >= t; case 7: return s > t;
            case 8: return s == 0f; case 9: return Float.isNaN(s); case 10: return Float.isInfinite(s); case 11: return Float.isNaN(s) || Float.isInfinite(s);
            case 12: return s != 0f; case 13: return !Float.isNaN(s); case 14: return !Float.isInfinite(s); default: return !(Float.isNaN(s) || Float.isInfinite(s));
        }
    }
    private void vfpu3(int op, int pc) {
        int n = size(op); float[] s = readS(op, n), t = readT(op, n), d = new float[4];
        switch ((op >>> 23) & 7) {
            case 0: { // vcmp
                int cc = 0, any = 0, all = 1, affected = 0x30;
                for (int i = 0; i < n; i++) { int c = compare(op & 15, s[i], t[i]) ? 1 : 0; cc |= c << i; any |= c; all &= c; affected |= 1 << i; }
                ctrl[CC] = (ctrl[CC] & ~affected) | ((cc | (any << 4) | (all << 5)) & affected);
                return;
            }
            case 2: for (int i = 0; i < n; i++) d[i] = Math.min(s[i], t[i]); break;
            case 3: for (int i = 0; i < n; i++) d[i] = Math.max(s[i], t[i]); break;
            case 5: for (int i = 0; i < n; i++) { float x = s[i] - t[i]; d[i] = x > 0 ? 1f : x < 0 ? -1f : 0f; } break; // vscmp
            case 6: for (int i = 0; i < n; i++) d[i] = s[i] >= t[i] ? 1f : 0f; break;
            case 7: for (int i = 0; i < n; i++) d[i] = s[i] < t[i] ? 1f : 0f; break;
            default: throw unsupported(op, pc);
        }
        write(op & 0x7f, n, d);
    }
    private static float sin90(double x) { double r = x % 4.0; if (r == 0 || r == 2 || r == -2) return 0f; return (float)Math.sin(Math.PI / 2 * x); }
    private static float cos90(double x) { double r = Math.abs(x % 4.0); if (r == 1 || r == 3) return 0f; return (float)Math.cos(Math.PI / 2 * x); }
    private int nextRandom() { random ^= random << 13; random ^= random >>> 17; random ^= random << 5; return random; }
    private static int toInt(double x) {
        if (Double.isNaN(x)) return 0x7fffffff;
        if (x >= 2147483647.0) return 0x7fffffff; if (x <= -2147483648.0) return 0x80000000; return (int)x;
    }
    private static int toHalf(float f) {
        int bits = Float.floatToRawIntBits(f), sign = (bits >>> 16) & 0x8000, exp = ((bits >>> 23) & 0xff) - 127 + 15, mant = bits & 0x7fffff;
        if (((bits >>> 23) & 0xff) == 0xff) return sign | 0x7c00 | (mant != 0 ? 0x200 | (mant >>> 13) : 0);
        if (exp >= 31) return sign | 0x7c00;
        if (exp <= 0) { if (exp < -10) return sign; mant |= 0x800000; return sign | (mant >>> (14 - exp)); }
        return sign | (exp << 10) | (mant >>> 13);
    }
    static float fromHalf(int h) {
        int sign = (h & 0x8000) << 16, exp = (h >>> 10) & 31, mant = h & 0x3ff;
        if (exp == 31) return Float.intBitsToFloat(sign | 0x7f800000 | (mant << 13));
        if (exp == 0) { float value = mant / 1024f / 16384f; return sign != 0 ? -value : value; }
        return Float.intBitsToFloat(sign | ((exp - 15 + 127) << 23) | (mant << 13));
    }
    private void vfpu4(int op, int pc) {
        int n = size(op), vd = op & 0x7f, vs = (op >>> 8) & 0x7f;
        int group = (op >>> 21) & 31, sub = (op >>> 16) & 31;
        float[] d = new float[4];
        if (group == 0) {
            float[] s = readS(op, n);
            switch (sub) {
                case 0: System.arraycopy(s, 0, d, 0, 4); break;
                case 1: for (int i = 0; i < n; i++) d[i] = Math.abs(s[i]); break;
                case 2: for (int i = 0; i < n; i++) d[i] = -s[i]; break;
                case 3: for (int i = 0; i < n; i++) d[i] = (vd & 3) == i ? 1f : 0f; break; // vidt
                case 4: for (int i = 0; i < n; i++) d[i] = Float.isNaN(s[i]) ? s[i] : Math.max(0f, Math.min(1f, s[i])); break;
                case 5: for (int i = 0; i < n; i++) d[i] = Float.isNaN(s[i]) ? s[i] : Math.max(-1f, Math.min(1f, s[i])); break;
                case 6: break; // vzero
                case 7: for (int i = 0; i < n; i++) d[i] = 1f; break;
                case 16: for (int i = 0; i < n; i++) d[i] = 1f / s[i]; break;
                case 17: for (int i = 0; i < n; i++) d[i] = (float)(1 / Math.sqrt(s[i])); break;
                case 18: for (int i = 0; i < n; i++) d[i] = sin90(s[i]); break;
                case 19: for (int i = 0; i < n; i++) d[i] = cos90(s[i]); break;
                case 20: for (int i = 0; i < n; i++) d[i] = (float)Math.pow(2, s[i]); break;
                case 21: for (int i = 0; i < n; i++) d[i] = (float)(Math.log(s[i]) / Math.log(2)); break;
                case 22: for (int i = 0; i < n; i++) d[i] = (float)Math.sqrt(s[i]); break;
                case 23: for (int i = 0; i < n; i++) d[i] = (float)(Math.asin(s[i]) * 2 / Math.PI); break;
                case 24: for (int i = 0; i < n; i++) d[i] = -1f / s[i]; break;
                case 26: for (int i = 0; i < n; i++) d[i] = -sin90(s[i]); break;
                case 28: for (int i = 0; i < n; i++) d[i] = (float)(1 / Math.pow(2, s[i])); break;
                default: throw unsupported(op, pc);
            }
            write(vd, n, d); return;
        }
        if (group == 1) {
            int[] s = readRaw(vs, n), out = new int[4];
            switch (sub) {
                case 0: random = s[0] == 0 ? 0x3f800001 : s[0]; return; // vrnds
                case 1: for (int i = 0; i < n; i++) out[i] = nextRandom(); writeRaw(vd, n, out); return;
                case 2: for (int i = 0; i < n; i++) d[i] = 1f + (nextRandom() >>> 9) / 8388608f; write(vd, n, d); return;
                case 3: for (int i = 0; i < n; i++) d[i] = 2f + (nextRandom() >>> 9) / 4194304f; write(vd, n, d); return;
                case 18: { // vf2h
                    float[] f = readS(op, n); for (int i = 0; i < n / 2; i++) out[i] = toHalf(f[i * 2]) | (toHalf(f[i * 2 + 1]) << 16);
                    writeRaw(vd, n / 2, out); return;
                }
                case 19: { // vh2f
                    for (int i = 0; i < n; i++) { d[i * 2] = fromHalf(s[i] & 0xffff); d[i * 2 + 1] = fromHalf(s[i] >>> 16); }
                    if (n > 2) throw unsupported(op, pc); write(vd, n * 2, d); return;
                }
                case 24: { int x = s[0]; for (int i = 0; i < 4; i++) { out[i] = ((x & 0xff) * 0x01010101) >>> 1; x >>>= 8; } writeRaw(vd, 4, out); return; } // vuc2i
                case 25: { int x = s[0]; out[0] = (x & 0xff) << 24; out[1] = (x & 0xff00) << 16; out[2] = (x & 0xff0000) << 8; out[3] = x & 0xff000000; writeRaw(vd, 4, out); return; } // vc2i
                case 26: case 27: { // vus2i, vs2i
                    if (n > 2) throw unsupported(op, pc);
                    for (int i = 0; i < n; i++) {
                        if (sub == 26) { out[i*2] = (s[i] & 0xffff) << 15; out[i*2+1] = (s[i] >>> 16) << 15; }
                        else { out[i*2] = (s[i] & 0xffff) << 16; out[i*2+1] = s[i] & 0xffff0000; }
                    }
                    writeRaw(vd, n * 2, out); return;
                }
                case 28: case 29: { // vi2uc, vi2c
                    if (n != 4) throw unsupported(op, pc); int x = 0;
                    for (int i = 0; i < 4; i++) x |= (sub == 28 ? (s[i] < 0 ? 0 : (s[i] >>> 23) & 0xff) : (s[i] >>> 24) & 0xff) << (8 * i);
                    out[0] = x; writeRaw(vd, 1, out); return;
                }
                case 30: case 31: { // vi2us, vi2s
                    if (n != 2 && n != 4) throw unsupported(op, pc);
                    for (int i = 0; i < n / 2; i++) {
                        int lo = s[i*2], hi = s[i*2+1];
                        out[i] = sub == 30 ? ((lo < 0 ? 0 : (lo >>> 15) & 0xffff) | ((hi < 0 ? 0 : (hi >>> 15) & 0xffff) << 16)) : (((lo >>> 16) & 0xffff) | (hi & 0xffff0000));
                    }
                    writeRaw(vd, n / 2, out); return;
                }
                default: throw unsupported(op, pc);
            }
        }
        if (group == 2) {
            float[] s = readS(op, n);
            switch (sub) {
                case 0: d[0] = Math.min(s[0], s[1]); d[1] = Math.max(s[0], s[1]); d[2] = Math.min(s[2], s[3]); d[3] = Math.max(s[2], s[3]); break; // vsrt1
                case 1: d[0] = Math.min(s[0], s[3]); d[1] = Math.min(s[1], s[2]); d[2] = Math.max(s[1], s[2]); d[3] = Math.max(s[0], s[3]); break; // vsrt2
                case 2: d[0] = s[0] + s[1]; d[1] = s[0] - s[1]; if (n == 4) { d[2] = s[2] + s[3]; d[3] = s[2] - s[3]; } break; // vbfy1
                case 3: d[0] = s[0] + s[2]; d[1] = s[1] + s[3]; d[2] = s[0] - s[2]; d[3] = s[1] - s[3]; break; // vbfy2
                case 4: for (int i = 0; i < n; i++) d[i] = 1f - s[i]; break; // vocp
                case 5: { // vsocp
                    if (n > 2) throw unsupported(op, pc);
                    for (int i = 0; i < n; i++) { d[i*2] = Math.max(0f, Math.min(1f, 1f - s[i])); d[i*2+1] = Math.max(0f, Math.min(1f, s[i])); }
                    write(vd, n * 2, d); return;
                }
                case 6: { float sum = 0; for (int i = 0; i < n; i++) sum += s[i]; d[0] = sum; write(vd, 1, d); return; } // vfad
                case 7: { float sum = 0; for (int i = 0; i < n; i++) sum += s[i]; d[0] = sum / n; write(vd, 1, d); return; } // vavg
                case 8: d[0] = Math.max(s[0], s[1]); d[1] = Math.min(s[0], s[1]); d[2] = Math.max(s[2], s[3]); d[3] = Math.min(s[2], s[3]); break; // vsrt3
                case 9: d[0] = Math.max(s[0], s[3]); d[1] = Math.max(s[1], s[2]); d[2] = Math.min(s[1], s[2]); d[3] = Math.min(s[0], s[3]); break; // vsrt4
                case 10: for (int i = 0; i < n; i++) d[i] = Math.signum(s[i]); break; // vsgn
                default: throw unsupported(op, pc);
            }
            write(vd, n, d); return;
        }
        if (group == 3) { d[0] = d[1] = d[2] = d[3] = VCST[sub]; write(vd, n, d); return; } // vcst
        if (group >= 16 && group <= 19) { // vf2in/vf2iz/vf2iu/vf2id
            float[] s = readS(op, n); int[] out = new int[4];
            for (int i = 0; i < n; i++) {
                double x = (double)s[i] * Math.pow(2, sub);
                switch (group) { case 16: x = Math.rint(x); break; case 17: x = x < 0 ? Math.ceil(x) : Math.floor(x); break; case 18: x = Math.ceil(x); break; default: x = Math.floor(x); }
                out[i] = toInt(x);
            }
            writeRaw(vd, n, out); return;
        }
        if (group == 20) { int[] s = readRaw(vs, n); for (int i = 0; i < n; i++) d[i] = (float)(s[i] / Math.pow(2, sub)); write(vd, n, d); return; } // vi2f
        if (group == 21) { // vcmovt / vcmovf
            float[] s = readS(op, n); int imm = sub & 7; boolean onTrue = ((sub >>> 3) & 3) == 0;
            int[] r = vector(vd, n); float[] cur = new float[4]; for (int i = 0; i < n; i++) cur[i] = f(r[i]);
            for (int i = 0; i < n; i++) {
                int bit = imm < 6 ? cc(imm) : imm == 6 ? cc(i) : 1;
                if (imm == 7) cur[i] = onTrue ? cur[i] : s[i]; // imm 7 never selects for vcmovt, always for vcmovf
                else if ((bit != 0) == onTrue) cur[i] = s[i];
            }
            write(vd, n, cur); return;
        }
        throw unsupported(op, pc);
    }
    private void vfpu5(int op, int pc) {
        switch ((op >>> 24) & 3) {
            case 0: ctrl[SPFX] = op & 0xfffff; break;
            case 1: ctrl[TPFX] = op & 0xfffff; break;
            case 2: ctrl[DPFX] = op & 0xfff; break;
            default: {
                int vt = (op >>> 16) & 0x7f; float value = ((op >>> 23) & 1) == 0 ? (float)(short)op : fromHalf(op & 0xffff);
                write(vt, 1, new float[]{value, 0, 0, 0}); eat(); break;
            }
        }
    }
    private void vfpu6(int op, int pc) {
        int ins = (op >>> 23) & 7, n = size(op), vd = op & 0x7f, vs = (op >>> 8) & 0x7f, vt = (op >>> 16) & 0x7f;
        switch (ins) {
            case 0: { // vmmul
                float[] s = readMatrix(vs, n), t = readMatrix(vt, n), d = new float[16];
                for (int a = 0; a < n; a++) for (int b = 0; b < n; b++) { float sum = 0; for (int c = 0; c < n; c++) sum += s[b*4+c] * t[a*4+c]; d[a*4+b] = sum; }
                writeMatrix(vd, n, d); return;
            }
            case 1: case 2: case 3: { // vtfm / vhtfm
                int side = ins + 1; boolean homogeneous = n == ins; if (!homogeneous && n != side) throw unsupported(op, pc);
                float[] s = readMatrix(vs, side), t = read(vt, n, 0xe4), d = new float[4];
                if (homogeneous) t[side - 1] = 1f;
                for (int i = 0; i < side; i++) { float sum = 0; for (int j = 0; j < side; j++) sum += s[i*4+j] * t[j]; d[i] = sum; }
                write(vd, side, d); return;
            }
            case 4: { // vmscl
                float[] s = readMatrix(vs, n), d = new float[16]; float k = f(vector(vt, 1)[0]);
                for (int i = 0; i < 16; i++) d[i] = s[i] * k; writeMatrix(vd, n, d); return;
            }
            case 5: { // vcrsp.t / vqmul.q
                float[] s = readS(op, n), t = readT(op, n), d = new float[4];
                if (n == 3) { d[0] = s[1]*t[2] - s[2]*t[1]; d[1] = s[2]*t[0] - s[0]*t[2]; d[2] = s[0]*t[1] - s[1]*t[0]; }
                else if (n == 4) {
                    d[0] = s[0]*t[3] + s[1]*t[2] - s[2]*t[1] + s[3]*t[0];
                    d[1] = -s[0]*t[2] + s[1]*t[3] + s[2]*t[0] + s[3]*t[1];
                    d[2] = s[0]*t[1] - s[1]*t[0] + s[2]*t[3] + s[3]*t[2];
                    d[3] = -s[0]*t[0] - s[1]*t[1] - s[2]*t[2] + s[3]*t[3];
                } else throw unsupported(op, pc);
                write(vd, n, d); return;
            }
            case 7: {
                if (((op >>> 21) & 3) == 1) { // vrot
                    int imm = (op >>> 16) & 31; float angle = read(vs, 1, ctrl[SPFX])[0];
                    float sine = sin90(angle), cosine = cos90(angle); if ((imm & 16) != 0) sine = -sine;
                    float[] d = new float[4];
                    if (((imm >>> 2) & 3) == (imm & 3)) for (int i = 0; i < 4; i++) d[i] = sine;
                    d[(imm >>> 2) & 3] = sine; d[imm & 3] = cosine; write(vd, n, d); return;
                }
                float[] d = new float[16];
                switch ((op >>> 16) & 15) {
                    case 0: d = readMatrix(vs, n); break; // vmmov
                    case 3: for (int i = 0; i < n; i++) d[i*4+i] = 1f; break; // vmidt
                    case 6: break; // vmzero
                    case 7: java.util.Arrays.fill(d, 1f); break; // vmone
                    default: throw unsupported(op, pc);
                }
                writeMatrix(vd, n, d); return;
            }
            default: throw unsupported(op, pc);
        }
    }
}
