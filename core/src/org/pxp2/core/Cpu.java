package org.pxp2.core;

/**
 * Original Allegrex (PSP MIPS32 R2 subset) interpreter with FPU and VFPU.
 * Unsupported instructions stop instead of pretending to execute. Guest SYSCALLs are handed to
 * an installed {@link Syscalls} handler; with none installed they stop execution.
 */
public final class Cpu {
    public interface Syscalls { void syscall(Cpu cpu, int code); }

    /** Saved register state of a guest thread. */
    public static final class Context {
        public final int[] r = new int[32], f = new int[32];
        public int pc, next, hi, lo, fcr31; public boolean delay;
        public final Vfpu vfpu = new Vfpu();
    }

    public final Memory memory;
    private final byte[] ram; // main RAM, for inline fast paths
    private final int ramMask; // Memory.ramMask: ~(RAM size - 1)
    private final int[] r = new int[32];
    private final int[] f = new int[32]; // FPU registers as raw bits
    private int pc, next, hi, lo, fcr31;
    private boolean delay, llBit, interrupts = true;
    public final Vfpu vfpu = new Vfpu();
    public Syscalls syscalls;
    public long cycles;
    /** Number of SYSCALL instructions executed; lets the kernel loop detect guest-to-kernel transfers cheaply. */
    public long traps;

    public Cpu(Memory memory, int entry) { this.memory = memory; ram = memory.ram; ramMask = memory.ramMask; pc = entry; next = entry + 4; }
    public int register(int n) { return r[n]; }
    public void setRegister(int n, int value) { if (n != 0) r[n] = value; }
    public float fpr(int n) { return Float.intBitsToFloat(f[n]); }
    public void setFpr(int n, float value) { f[n] = Float.floatToRawIntBits(value); }
    public int hi() { return hi; }
    public int lo() { return lo; }
    public void returnToCaller() { jump(r[31]); }
    public void jump(int target) { pc = target; next = target + 4; delay = false; }
    public int pc() { return pc; }
    public boolean inDelaySlot() { return delay; }

    public void save(Context c) {
        System.arraycopy(r, 0, c.r, 0, 32); System.arraycopy(f, 0, c.f, 0, 32);
        c.pc = pc; c.next = next; c.hi = hi; c.lo = lo; c.fcr31 = fcr31; c.delay = delay; c.vfpu.copyFrom(vfpu);
    }
    public void load(Context c) {
        System.arraycopy(c.r, 0, r, 0, 32); System.arraycopy(c.f, 0, f, 0, 32);
        pc = c.pc; next = c.next; hi = c.hi; lo = c.lo; fcr31 = c.fcr31; delay = c.delay; vfpu.copyFrom(c.vfpu); llBit = false; r[0] = 0;
    }

    private IllegalStateException delaySlot() { return new IllegalStateException(String.format("Control transfer in delay slot at %08x", pc)); }

    /**
     * Executes one instruction. Hot opcodes are handled inline with direct main-RAM fast paths
     * (same offsets/alignment rules as {@link Memory}); rarer groups live in helper methods so this
     * method stays compact for ART's JIT. Control transfers check the delay slot themselves.
     */
    public void step() {
        final int[] r = this.r; final byte[] m = ram; final int k = ramMask; // k = ~(RAM size - 1), see Memory.ramMask
        int i, o = (pc & 0x1fffffff) - Memory.BASE;
        if ((o & (k | 3)) == 0) i = (m[o] & 255) | (m[o+1] & 255) << 8 | (m[o+2] & 255) << 16 | m[o+3] << 24;
        else i = memory.read32(pc);
        int rs = i >>> 21 & 31, rt = i >>> 16 & 31;
        int following = next + 4;
        boolean branch = false, annul = false;
        switch (i >>> 26) {
            case 0: {
                int rd = i >>> 11 & 31;
                switch (i & 63) {
                    case 0: r[rd] = r[rt] << (i >>> 6 & 31); break;
                    case 8: if (delay) throw delaySlot(); following = r[rs]; branch = true; break;
                    case 9: { if (delay) throw delaySlot(); int target = r[rs]; r[rd] = pc + 8; following = target; branch = true; break; }
                    case 33: r[rd] = r[rs] + r[rt]; break;
                    case 35: r[rd] = r[rs] - r[rt]; break;
                    case 36: r[rd] = r[rs] & r[rt]; break;
                    case 37: r[rd] = r[rs] | r[rt]; break;
                    case 43: r[rd] = Integer.compareUnsigned(r[rs], r[rt]) < 0 ? 1 : 0; break;
                    default: special(i, rs, rt, rd);
                }
                break;
            }
            case 1: {
                if (delay) throw delaySlot();
                if (rt != 0 && rt != 1 && rt != 2 && rt != 3 && rt != 16 && rt != 17 && rt != 18 && rt != 19) throw unsupported(i);
                boolean taken = (rt & 1) == 0 ? r[rs] < 0 : r[rs] >= 0;
                if ((rt & 16) != 0) r[31] = pc + 8;
                if (taken) following = pc + 4 + ((short)i << 2); else annul = (rt & 2) != 0;
                branch = true; break;
            }
            case 2: case 3:
                if (delay) throw delaySlot();
                if (i >>> 26 == 3) r[31] = pc + 8;
                following = ((pc + 4) & 0xf0000000) | ((i & 0x03ffffff) << 2); branch = true; break;
            case 4: if (delay) throw delaySlot(); if (r[rs] == r[rt]) following = pc + 4 + ((short)i << 2); branch = true; break;
            case 5: if (delay) throw delaySlot(); if (r[rs] != r[rt]) following = pc + 4 + ((short)i << 2); branch = true; break;
            case 6: if (delay) throw delaySlot(); if (r[rs] <= 0) following = pc + 4 + ((short)i << 2); branch = true; break;
            case 7: if (delay) throw delaySlot(); if (r[rs] > 0) following = pc + 4 + ((short)i << 2); branch = true; break;
            case 20: if (delay) throw delaySlot(); if (r[rs] == r[rt]) following = pc + 4 + ((short)i << 2); else annul = true; branch = true; break;
            case 21: if (delay) throw delaySlot(); if (r[rs] != r[rt]) following = pc + 4 + ((short)i << 2); else annul = true; branch = true; break;
            case 22: if (delay) throw delaySlot(); if (r[rs] <= 0) following = pc + 4 + ((short)i << 2); else annul = true; branch = true; break;
            case 23: if (delay) throw delaySlot(); if (r[rs] > 0) following = pc + 4 + ((short)i << 2); else annul = true; branch = true; break;
            case 8: case 9: r[rt] = r[rs] + (short)i; break;
            case 10: r[rt] = r[rs] < (short)i ? 1 : 0; break;
            case 11: r[rt] = Integer.compareUnsigned(r[rs], (short)i) < 0 ? 1 : 0; break;
            case 12: r[rt] = r[rs] & (i & 65535); break;
            case 13: r[rt] = r[rs] | (i & 65535); break;
            case 14: r[rt] = r[rs] ^ (i & 65535); break;
            case 15: r[rt] = i << 16; break;
            case 17: // COP1; bc1f/bc1t/bc1fl/bc1tl are branches
                if (rs == 8) {
                    if (delay) throw delaySlot();
                    if (((fcr31 & (1 << 23)) != 0) == ((rt & 1) != 0)) following = pc + 4 + ((short)i << 2); else annul = (rt & 2) != 0;
                    branch = true;
                } else cop1(i, rs, rt, i >>> 11 & 31, i >>> 6 & 31);
                break;
            case 18: // COP2: VFPU moves and branches
                if (rs == 8) {
                    if (delay) throw delaySlot();
                    int bit = vfpu.cc((i >>> 18) & 7), type = (i >>> 16) & 3;
                    if ((bit != 0) == ((type & 1) != 0)) following = pc + 4 + ((short)i << 2); else annul = (type & 2) != 0;
                    branch = true;
                } else if (rs == 3) { if (rt != 0) r[rt] = vfpu.moveFrom(i); }
                else if (rs == 7) vfpu.moveTo(i, r[rt]);
                else throw unsupported(i);
                break;
            case 24: case 25: case 27: case 52: case 55: case 60: case 63: vfpu.execute(i, pc); break;
            case 28: case 31: special3(i, rs, rt); break;
            case 32: { int a = r[rs] + (short)i; o = (a & 0x1fffffff) - Memory.BASE; r[rt] = (o & k) == 0 ? m[o] : (byte)memory.read8(a); break; }
            case 33: { int a = r[rs] + (short)i; o = (a & 0x1fffffff) - Memory.BASE; r[rt] = (o & (k | 1)) == 0 ? (short)((m[o] & 255) | m[o+1] << 8) : (short)memory.read16(a); break; }
            case 35: { int a = r[rs] + (short)i; o = (a & 0x1fffffff) - Memory.BASE;
                r[rt] = (o & (k | 3)) == 0 ? (m[o] & 255) | (m[o+1] & 255) << 8 | (m[o+2] & 255) << 16 | m[o+3] << 24 : memory.read32(a); break; }
            case 36: { int a = r[rs] + (short)i; o = (a & 0x1fffffff) - Memory.BASE; r[rt] = (o & k) == 0 ? m[o] & 255 : memory.read8(a); break; }
            case 37: { int a = r[rs] + (short)i; o = (a & 0x1fffffff) - Memory.BASE; r[rt] = (o & (k | 1)) == 0 ? (m[o] & 255) | (m[o+1] & 255) << 8 : memory.read16(a); break; }
            case 40: { int a = r[rs] + (short)i; o = (a & 0x1fffffff) - Memory.BASE; if ((o & k) == 0) m[o] = (byte)r[rt]; else memory.write8(a, r[rt]); break; }
            case 41: { int a = r[rs] + (short)i, v = r[rt]; o = (a & 0x1fffffff) - Memory.BASE; if ((o & (k | 1)) == 0) { m[o] = (byte)v; m[o+1] = (byte)(v >>> 8); } else memory.write16(a, v); break; }
            case 43: { int a = r[rs] + (short)i, v = r[rt]; o = (a & 0x1fffffff) - Memory.BASE;
                if ((o & (k | 3)) == 0) { m[o] = (byte)v; m[o+1] = (byte)(v >>> 8); m[o+2] = (byte)(v >>> 16); m[o+3] = (byte)(v >>> 24); } else memory.write32(a, v); break; }
            case 47: break; // CACHE: no cache model; interpreter never caches decoded code.
            case 49: { int a = r[rs] + (short)i; o = (a & 0x1fffffff) - Memory.BASE; // lwc1
                f[rt] = (o & (k | 3)) == 0 ? (m[o] & 255) | (m[o+1] & 255) << 8 | (m[o+2] & 255) << 16 | m[o+3] << 24 : memory.read32(a); break; }
            case 57: { int a = r[rs] + (short)i, v = f[rt]; o = (a & 0x1fffffff) - Memory.BASE; // swc1
                if ((o & (k | 3)) == 0) { m[o] = (byte)v; m[o+1] = (byte)(v >>> 8); m[o+2] = (byte)(v >>> 16); m[o+3] = (byte)(v >>> 24); } else memory.write32(a, v); break; }
            default: memoryOp(i, rs, rt);
        }
        r[0] = 0;
        cycles++;
        if (annul) { pc = next + 4; next = following + 4; delay = false; }
        else { pc = next; next = following; delay = branch; }
    }

    /** SPECIAL (opcode 0) functions other than the inlined hot ones; none of these are branches. */
    private void special(int i, int rs, int rt, int rd) {
        final int[] r = this.r; int shift = i >>> 6 & 31;
        switch (i & 63) {
            case 2: if (rs == 1) r[rd] = Integer.rotateRight(r[rt], shift); else if (rs == 0) r[rd] = r[rt] >>> shift; else throw unsupported(i); break;
            case 3: r[rd] = r[rt] >> shift; break;
            case 4: r[rd] = r[rt] << (r[rs] & 31); break;
            case 6: if (shift == 1) r[rd] = Integer.rotateRight(r[rt], r[rs] & 31); else if (shift == 0) r[rd] = r[rt] >>> (r[rs] & 31); else throw unsupported(i); break;
            case 7: r[rd] = r[rt] >> (r[rs] & 31); break;
            case 10: if (r[rt] == 0) r[rd] = r[rs]; break;
            case 11: if (r[rt] != 0) r[rd] = r[rs]; break;
            case 12:
                if (syscalls == null) throw new IllegalStateException("PSP syscall requires an emulated kernel");
                traps++; syscalls.syscall(this, (i >>> 6) & 0xfffff); break;
            case 13: throw new IllegalStateException("MIPS BREAK");
            case 15: break; // SYNC: interpreter memory operations are ordered.
            case 16: r[rd] = hi; break;
            case 17: hi = r[rs]; break;
            case 18: r[rd] = lo; break;
            case 19: lo = r[rs]; break;
            case 22: r[rd] = Integer.numberOfLeadingZeros(r[rs]); break;
            case 23: r[rd] = Integer.numberOfLeadingZeros(~r[rs]); break;
            case 24: { long p = (long)r[rs] * r[rt]; lo = (int)p; hi = (int)(p >>> 32); break; }
            case 25: { long p = Integer.toUnsignedLong(r[rs]) * Integer.toUnsignedLong(r[rt]); lo = (int)p; hi = (int)(p >>> 32); break; }
            case 26: if (r[rt] == 0) { lo = r[rs] < 0 ? 1 : -1; hi = r[rs]; } else if (r[rs] == Integer.MIN_VALUE && r[rt] == -1) { lo = Integer.MIN_VALUE; hi = 0; } else { lo = r[rs] / r[rt]; hi = r[rs] % r[rt]; } break;
            case 27: if (r[rt] == 0) { lo = -1; hi = r[rs]; } else { lo = Integer.divideUnsigned(r[rs], r[rt]); hi = Integer.remainderUnsigned(r[rs], r[rt]); } break;
            case 28: { long acc = ((long)hi << 32 | Integer.toUnsignedLong(lo)) + (long)r[rs] * r[rt]; lo = (int)acc; hi = (int)(acc >>> 32); break; } // madd
            case 29: { long acc = ((long)hi << 32 | Integer.toUnsignedLong(lo)) + Integer.toUnsignedLong(r[rs]) * Integer.toUnsignedLong(r[rt]); lo = (int)acc; hi = (int)(acc >>> 32); break; } // maddu
            case 32: r[rd] = r[rs] + r[rt]; break; // add (overflow traps are not generated by PSP code in practice)
            case 34: r[rd] = r[rs] - r[rt]; break;
            case 38: r[rd] = r[rs] ^ r[rt]; break;
            case 39: r[rd] = ~(r[rs] | r[rt]); break;
            case 42: r[rd] = r[rs] < r[rt] ? 1 : 0; break;
            case 44: r[rd] = Math.max(r[rs], r[rt]); break;
            case 45: r[rd] = Math.min(r[rs], r[rt]); break;
            case 46: { long acc = ((long)hi << 32 | Integer.toUnsignedLong(lo)) - (long)r[rs] * r[rt]; lo = (int)acc; hi = (int)(acc >>> 32); break; } // msub
            case 47: { long acc = ((long)hi << 32 | Integer.toUnsignedLong(lo)) - Integer.toUnsignedLong(r[rs]) * Integer.toUnsignedLong(r[rt]); lo = (int)acc; hi = (int)(acc >>> 32); break; } // msubu
            default: throw unsupported(i);
        }
    }

    /** SPECIAL2 (28: mfic/mtic) and SPECIAL3 (31: ext/ins/seb/seh/wsbh/wsbw/bitrev). */
    private void special3(int i, int rs, int rt) {
        final int[] r = this.r; int rd = i >>> 11 & 31, shift = i >>> 6 & 31;
        if (i >>> 26 == 28) {
            switch (i & 63) {
                case 0x24: r[rt] = interrupts ? 1 : 0; break; // mfic
                case 0x26: interrupts = r[rt] != 0; break;    // mtic
                default: throw unsupported(i);
            }
            return;
        }
        switch (i & 63) {
            case 0: if (shift + rd + 1 > 32) throw unsupported(i); r[rt] = (r[rs] >>> shift) & (int)(0xffffffffL >>> (31 - rd)); break;
            case 4: { if (rd < shift) throw unsupported(i); int mask = (int)((0xffffffffL >>> (31 - rd + shift)) << shift); r[rt] = (r[rt] & ~mask) | ((r[rs] << shift) & mask); break; }
            case 32:
                switch (shift) {
                    case 2: r[rd] = ((r[rt] & 0xff00ff00) >>> 8) | ((r[rt] & 0x00ff00ff) << 8); break;
                    case 3: r[rd] = Integer.reverseBytes(r[rt]); break;
                    case 16: r[rd] = (byte)r[rt]; break;
                    case 20: r[rd] = Integer.reverse(r[rt]); break;
                    case 24: r[rd] = (short)r[rt]; break;
                    default: throw unsupported(i);
                }
                break;
            default: throw unsupported(i);
        }
    }

    /** Less common loads/stores: unaligned lwl/lwr/swl/swr, ll/sc and VFPU transfers. */
    private void memoryOp(int i, int rs, int rt) {
        final int[] r = this.r; int imm = (short)i;
        switch (i >>> 26) {
            case 34: { int a = r[rs] + imm, s = (a & 3) * 8, w = memory.read32(a & ~3); r[rt] = (r[rt] & (0x00ffffff >>> s)) | (w << (24 - s)); break; } // lwl
            case 38: { int a = r[rs] + imm, s = (a & 3) * 8, w = memory.read32(a & ~3); r[rt] = (r[rt] & (int)(0xffffff00L << (24 - s))) | (w >>> s); break; } // lwr
            case 42: { int a = r[rs] + imm, s = (a & 3) * 8, w = memory.read32(a & ~3); memory.write32(a & ~3, (w & (int)(0xffffff00L << s)) | (r[rt] >>> (24 - s))); break; } // swl
            case 46: { int a = r[rs] + imm, s = (a & 3) * 8, w = memory.read32(a & ~3); memory.write32(a & ~3, (w & (0x00ffffff >>> (24 - s))) | (r[rt] << s)); break; } // swr
            case 48: r[rt] = memory.read32(r[rs] + imm); llBit = true; break; // ll
            case 50: case 53: case 54: vfpu.load(memory, i, r[rs] + (imm & ~3), pc); break;
            case 56: if (llBit) { memory.write32(r[rs] + imm, r[rt]); r[rt] = 1; } else r[rt] = 0; llBit = false; break; // sc
            case 58: case 61: case 62: vfpu.store(memory, i, r[rs] + (imm & ~3), pc); break;
            default: throw unsupported(i);
        }
    }

    private static int toWord(double x) {
        if (Double.isNaN(x) || x >= 2147483648.0) return 0x7fffffff;
        if (x < -2147483648.0) return 0x80000000;
        return (int)x;
    }
    private void cop1(int instruction, int rs, int rt, int rd, int fd) {
        switch (rs) {
            case 0: r[rt] = f[rd]; return;                                  // mfc1
            case 2: r[rt] = rd == 31 ? fcr31 : rd == 0 ? 0x3351 : 0; return;  // cfc1
            case 4: f[rd] = r[rt]; return;                                  // mtc1
            case 6: if (rd == 31) fcr31 = r[rt] & 0x0183ffff; return;        // ctc1
            case 16: {
                float s = Float.intBitsToFloat(f[rd]), t = Float.intBitsToFloat(f[rt]);
                int funct = instruction & 63;
                switch (funct) {
                    case 0: setFpr(fd, s + t); break;
                    case 1: setFpr(fd, s - t); break;
                    case 2: setFpr(fd, s * t); break;
                    case 3: setFpr(fd, s / t); break;
                    case 4: setFpr(fd, (float)Math.sqrt(s)); break;
                    case 5: f[fd] = f[rd] & 0x7fffffff; break;
                    case 6: f[fd] = f[rd]; break;
                    case 7: f[fd] = f[rd] ^ 0x80000000; break;
                    case 12: f[fd] = toWord(Math.rint(s)); break;
                    case 13: f[fd] = toWord(s < 0 ? Math.ceil(s) : Math.floor(s)); break;
                    case 14: f[fd] = toWord(Math.ceil(s)); break;
                    case 15: f[fd] = toWord(Math.floor(s)); break;
                    case 36: {
                        double x;
                        switch (fcr31 & 3) { case 0: x = Math.rint(s); break; case 1: x = s < 0 ? Math.ceil(s) : Math.floor(s); break; case 2: x = Math.ceil(s); break; default: x = Math.floor(s); }
                        f[fd] = toWord(x); break;
                    }
                    default:
                        if (funct >= 48) {
                            boolean unordered = Float.isNaN(s) || Float.isNaN(t);
                            boolean c = ((funct & 1) != 0 && unordered) || ((funct & 2) != 0 && !unordered && s == t) || ((funct & 4) != 0 && !unordered && s < t);
                            fcr31 = c ? fcr31 | (1 << 23) : fcr31 & ~(1 << 23);
                            break;
                        }
                        throw unsupported(instruction);
                }
                return;
            }
            case 20:
                if ((instruction & 63) == 32) { setFpr(fd, (float)f[rd]); return; } // cvt.s.w
                throw unsupported(instruction);
            default: throw unsupported(instruction);
        }
    }
    private IllegalStateException unsupported(int word) {
        return new IllegalStateException(String.format("Unsupported instruction %08x at %08x", word, pc));
    }
}
