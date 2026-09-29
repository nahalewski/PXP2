package org.pxp2.core;

import java.util.Locale;

/** Compact original Allegrex disassembler for diagnostics (integer, branch and memory forms). */
public final class Disasm {
    private static final String[] R = {"zr","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"};
    private static final String[] SPECIAL = new String[64], OPS = new String[64];
    static {
        String[][] s = {{"0","sll"},{"2","srl"},{"3","sra"},{"4","sllv"},{"6","srlv"},{"7","srav"},{"8","jr"},{"9","jalr"},{"10","movz"},{"11","movn"},{"12","syscall"},{"13","break"},{"15","sync"},
            {"16","mfhi"},{"17","mthi"},{"18","mflo"},{"19","mtlo"},{"22","clz"},{"23","clo"},{"24","mult"},{"25","multu"},{"26","div"},{"27","divu"},{"28","madd"},{"29","maddu"},
            {"32","add"},{"33","addu"},{"34","sub"},{"35","subu"},{"36","and"},{"37","or"},{"38","xor"},{"39","nor"},{"42","slt"},{"43","sltu"},{"44","max"},{"45","min"},{"46","msub"},{"47","msubu"}};
        for (String[] e : s) SPECIAL[Integer.parseInt(e[0])] = e[1];
        String[][] o = {{"2","j"},{"3","jal"},{"4","beq"},{"5","bne"},{"6","blez"},{"7","bgtz"},{"8","addi"},{"9","addiu"},{"10","slti"},{"11","sltiu"},{"12","andi"},{"13","ori"},{"14","xori"},{"15","lui"},
            {"20","beql"},{"21","bnel"},{"22","blezl"},{"23","bgtzl"},{"32","lb"},{"33","lh"},{"34","lwl"},{"35","lw"},{"36","lbu"},{"37","lhu"},{"38","lwr"},{"40","sb"},{"41","sh"},{"42","swl"},{"43","sw"},{"46","swr"},
            {"47","cache"},{"48","ll"},{"49","lwc1"},{"50","lv.s"},{"53","lvl/r.q"},{"54","lv.q"},{"56","sc"},{"57","swc1"},{"58","sv.s"},{"61","svl/r.q"},{"62","sv.q"}};
        for (String[] e : o) OPS[Integer.parseInt(e[0])] = e[1];
    }
    public static String instruction(int pc, int w) {
        int op = w >>> 26, rs = w >>> 21 & 31, rt = w >>> 16 & 31, rd = w >>> 11 & 31, sa = w >>> 6 & 31, imm = (short)w;
        if (w == 0) return "nop";
        switch (op) {
            case 0: {
                String n = SPECIAL[w & 63]; if (n == null) break;
                switch (w & 63) {
                    case 0: case 2: case 3: return String.format(Locale.ROOT, "%s %s, %s, %d", (w & 63) == 2 && rs == 1 ? "rotr" : n, R[rd], R[rt], sa);
                    case 8: return "jr " + R[rs];
                    case 9: return "jalr " + R[rd] + ", " + R[rs];
                    case 12: return String.format(Locale.ROOT, "syscall %x", (w >>> 6) & 0xfffff);
                    case 16: case 18: return n + " " + R[rd];
                    case 17: case 19: return n + " " + R[rs];
                    case 24: case 25: case 26: case 27: case 28: case 29: case 46: case 47: return n + " " + R[rs] + ", " + R[rt];
                    case 22: case 23: return n + " " + R[rd] + ", " + R[rs];
                    case 13: case 15: return n;
                    default: return String.format(Locale.ROOT, "%s %s, %s, %s", n, R[rd], R[rs], R[rt]);
                }
            }
            case 1: {
                String[] names = {"bltz","bgez","bltzl","bgezl"}; String n = (rt & 16) != 0 ? names[rt & 3].replace("z", "zal") : names[rt & 3];
                return String.format(Locale.ROOT, "%s %s, %08x", n, R[rs], pc + 4 + (imm << 2));
            }
            case 2: case 3: return String.format(Locale.ROOT, "%s %08x", OPS[op], ((pc + 4) & 0xf0000000) | ((w & 0x03ffffff) << 2));
            case 4: case 5: case 20: case 21: return String.format(Locale.ROOT, "%s %s, %s, %08x", OPS[op], R[rs], R[rt], pc + 4 + (imm << 2));
            case 6: case 7: case 22: case 23: return String.format(Locale.ROOT, "%s %s, %08x", OPS[op], R[rs], pc + 4 + (imm << 2));
            case 15: return String.format(Locale.ROOT, "lui %s, 0x%04x", R[rt], w & 0xffff);
            case 12: case 13: case 14: return String.format(Locale.ROOT, "%s %s, %s, 0x%04x", OPS[op], R[rt], R[rs], w & 0xffff);
            case 8: case 9: case 10: case 11: return String.format(Locale.ROOT, "%s %s, %s, %d", OPS[op], R[rt], R[rs], imm);
            case 31: {
                if ((w & 63) == 0) return String.format(Locale.ROOT, "ext %s, %s, %d, %d", R[rt], R[rs], sa, rd + 1);
                if ((w & 63) == 4) return String.format(Locale.ROOT, "ins %s, %s, %d, %d", R[rt], R[rs], sa, rd - sa + 1);
                if ((w & 63) == 32) return (sa == 16 ? "seb " : sa == 24 ? "seh " : sa == 2 ? "wsbh " : sa == 3 ? "wsbw " : "bitrev ") + R[rd] + ", " + R[rt];
                break;
            }
            case 17: return String.format(Locale.ROOT, "cop1 %08x", w);
            default:
                if (OPS[op] != null && op >= 32) {
                    boolean fpu = op == 49 || op == 57;
                    return String.format(Locale.ROOT, "%s %s, %d(%s)", OPS[op], fpu ? "f" + rt : op >= 50 ? "v" + rt : R[rt], imm, R[rs]);
                }
        }
        return String.format(Locale.ROOT, ".word 0x%08x", w);
    }
    public static String range(Memory m, int start, int count) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < count; i++) { int pc = start + i * 4; if (!m.valid(pc, 4)) break; s.append(String.format(Locale.ROOT, "%08x: %08x  %s\n", pc, m.read32(pc), instruction(pc, m.read32(pc)))); }
        return s.toString();
    }
}
