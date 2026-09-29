package org.pxp2.core;

import java.util.*;

/**
 * Original PSP graphics engine (GE) display-list processor. Lists are executed synchronously up to
 * their stall address; control flow (JUMP/CALL/RET/BASE/OFFSET/ORIGIN), SIGNAL/FINISH/END and the
 * register file are emulated. Primitive commands are counted and handed to an optional renderer;
 * without one nothing is rasterised (reported by {@link #primitives}).
 */
public final class Ge {
    public interface Renderer { void command(Ge ge, int command, int data, int address); default void flush() { } }
    public static final int EDRAM = 0x04000000, EDRAM_SIZE = 0x200000;
    public static final int DONE = 0, QUEUED = 1, DRAWING = 2, STALL = 3, PAUSED = 4;
    public static final class DisplayList {
        public final int id; public int start, pc, stall, callbackId, argument, state = QUEUED, base, offset;
        final int[] stack = new int[32]; int depth; boolean finished, signal;
        DisplayList(int id) { this.id = id; }
    }
    private final Kernel k; private final Memory m;
    public final int[] registers = new int[256];
    /**
     * The GE driver has a fixed table of display-list slots. A list holds its slot from sceGeListEnQueue until it
     * completes (or is dequeued), and the id returned to the caller names the slot. When every slot is in use,
     * enqueueing fails with SCE_ERROR_OUT_OF_MEMORY; enqueueing a list address that is still queued fails with
     * SCE_ERROR_BUSY. This also bounds the host: a guest that enqueues in a loop can never grow the queue.
     */
    public static final int SLOTS = 64;
    public static final int ERROR_BUSY = 0x80000021, ERROR_OUT_OF_MEMORY = 0x80000022, ERROR_INVALID_ID = 0x80000100,
        ERROR_INVALID_POINTER = 0x80000103, ERROR_INVALID_MODE = 0x80000107;
    private final DisplayList[] slots = new DisplayList[SLOTS];
    /** Lists not yet completed, in execution order (the head is the one the GE is working on). */
    public final List<DisplayList> lists = new ArrayList<>();
    private boolean processing, fullLogged;
    public long commands, primitives, finishedLists;
    public Renderer renderer;
    /** Callback slots registered through sceGeSetCallback: {signal, signalArg, finish, finishArg, gp}. */
    private final Map<Integer, int[]> callbacks = new HashMap<>();
    private int nextCallback;

    public Ge(Kernel k) { this.k = k; this.m = k.memory; register(); }

    private void register() {
        String lib = "sceGe_user";
        k.register(lib, 0xe47e40e4, "sceGeEdramGetAddr", c -> EDRAM);
        k.register(lib, 0x1f6752ad, "sceGeEdramGetSize", c -> EDRAM_SIZE);
        k.register(lib, 0xb77905ea, "sceGeEdramSetAddrTranslation", c -> 0);
        // sceGeEdramSetSize: PSP-2000 and later can map 4 MiB of EDRAM; PXP2 models the PSP-1000's 2 MiB, so larger sizes fail
        // with SCE_ERROR_INVALID_SIZE as on that model.
        k.register(lib, 0x5baa5439, "sceGeEdramSetSize", c -> Integer.compareUnsigned(c.arg(0), EDRAM_SIZE) <= 0 ? 0 : 0x80000104);
        k.register(lib, 0xab49e76a, "sceGeListEnQueue", c -> enqueue(c.arg(0), c.arg(1), c.arg(2), c.arg(3), false));
        k.register(lib, 0x1c0d95a6, "sceGeListEnQueueHead", c -> enqueue(c.arg(0), c.arg(1), c.arg(2), c.arg(3), true));
        k.register(lib, 0x5fb86ab0, "sceGeListDeQueue", c -> {
            DisplayList l = find(c.arg(0)); if (l == null) return ERROR_INVALID_ID;
            complete(l); process(); return 0;
        });
        k.register(lib, 0xe0d68148, "sceGeListUpdateStallAddr", c -> {
            DisplayList l = find(c.arg(0)); if (l == null) return ERROR_INVALID_ID;
            l.stall = c.arg(1) & 0x0fffffff; process(); return 0;
        });
        // Mode 0 waits until the list has completed; mode 1 peeks at its state. Lists run synchronously, so a list is
        // only unfinished while it waits at its stall address (or is paused): waiting blocks the thread until another
        // thread moves the stall address on.
        k.register(lib, 0x03444eb4, "sceGeListSync", c -> {
            int id = c.arg(0), mode = c.arg(1); if (id < 0 || id >= SLOTS) return ERROR_INVALID_ID;
            DisplayList l = find(id); int state = l == null ? DONE : l.state;
            if (mode == 1) return state;
            if (mode != 0) return ERROR_INVALID_MODE;
            if (state != DONE) k.waitCurrent(new Kernel.Wait("GeListSync", id), 0, false);
            return DONE;
        });
        // Mode 0 waits until the queue is empty; mode 1 reports the state of the list the GE is working on.
        k.register(lib, 0xb287bd61, "sceGeDrawSync", c -> {
            int mode = c.arg(0);
            if (mode == 1) return lists.isEmpty() ? DONE : lists.get(0).state == STALL ? STALL : DRAWING;
            if (mode != 0) return ERROR_INVALID_MODE;
            if (!lists.isEmpty()) k.waitCurrent(new Kernel.Wait("GeDrawSync", 0), 0, false);
            return DONE;
        });
        k.register(lib, 0xb448ec0d, "sceGeBreak", c -> { for (DisplayList l : lists) if (l.state == DRAWING || l.state == STALL) l.state = PAUSED; return 0; });
        k.register(lib, 0x4c06e472, "sceGeContinue", c -> { for (DisplayList l : lists) if (l.state == PAUSED) l.state = DRAWING; process(); return 0; });
        k.register(lib, 0xa4fc06a4, "sceGeSetCallback", c -> {
            int p = c.arg(0); int id = nextCallback++;
            callbacks.put(id, new int[]{m.read32(p), m.read32(p + 4), m.read32(p + 8), m.read32(p + 12), c.cpu.register(28)}); return id;
        });
        k.register(lib, 0x05db22ce, "sceGeUnsetCallback", c -> { callbacks.remove(c.arg(0)); return 0; });
        k.register(lib, 0xdc93cfef, "sceGeGetCmd", c -> c.arg(0) >= 0 && c.arg(0) < 256 ? registers[c.arg(0)] : 0x80000102);
        k.register(lib, 0x438a385a, "sceGeSaveContext", c -> { for (int i = 0; i < 256; i++) m.write32(c.arg(0) + i * 4, registers[i]); return 0; });
        k.register(lib, 0x0bf608fb, "sceGeRestoreContext", c -> { for (int i = 0; i < 256; i++) registers[i] = m.read32(c.arg(0) + i * 4); return 0; });
        k.register(lib, 0x57c8945b, "sceGeGetMtx", c -> 0);
    }
    /** The queued list with this id, or null if the id is invalid or its list has completed. */
    private DisplayList find(int id) { if (id < 0 || id >= SLOTS) return null; DisplayList l = slots[id]; return l != null && l.state != DONE ? l : null; }
    private int enqueue(int list, int stall, int callback, int option, boolean head) {
        list &= 0x0fffffff; // lists are passed cached, uncached (| 0x40000000) or through the 0x1fffffff mask
        if ((list & 3) != 0 || !m.valid(list, 4)) return ERROR_INVALID_POINTER;
        for (DisplayList q : lists) if (q.start == list) return ERROR_BUSY; // still queued: enqueueing it again is refused
        int slot = -1;
        for (int i = 0; i < SLOTS && slot < 0; i++) if (slots[i] == null || slots[i].state == DONE) slot = i;
        if (slot < 0) {
            if (!fullLogged) { fullLogged = true; k.log(String.format(Locale.ROOT, "GE: all %d display-list slots are in use; sceGeListEnQueue(%08x) returns %08x", SLOTS, list, ERROR_OUT_OF_MEMORY)); }
            return ERROR_OUT_OF_MEMORY;
        }
        DisplayList l = new DisplayList(slot); l.start = l.pc = list; l.stall = stall & 0x0fffffff; l.callbackId = callback;
        slots[slot] = l;
        if (head) lists.add(0, l); else lists.add(l);
        process(); return l.id;
    }
    /**
     * Runs the queue: the head list executes until it ends, stalls or is paused, and a list that ends leaves the queue
     * so the next one starts. Threads waiting in sceGeListSync / sceGeDrawSync are then woken if their list (or the
     * whole queue) has completed.
     */
    private void process() {
        if (processing) return;
        processing = true;
        try {
            while (!lists.isEmpty()) {
                DisplayList l = lists.get(0);
                if (l.state == PAUSED) break;
                if (l.stall != 0 && l.pc == l.stall) { l.state = STALL; break; }
                l.state = DRAWING; run(l);
                if (l.state != DONE) break;
                complete(l);
            }
        } finally { processing = false; }
        for (Kernel.GuestThread t : k.threads()) {
            Kernel.Wait w = t.wait; if (w == null) continue;
            if (w.type.equals("GeDrawSync") && lists.isEmpty()) k.wake(t, DONE);
            else if (w.type.equals("GeListSync") && find(w.id) == null) k.wake(t, DONE);
        }
    }
    private void complete(DisplayList l) { l.state = DONE; lists.remove(l); }
    private int address(DisplayList l, int data) { return ((((registers[0x10] & 0x000f0000) << 8) | (data & 0x00ffffff)) + l.offset) & 0x0fffffff; }
    /** Host time spent processing display lists (decode, tessellation, rasterisation). */
    public long nanos;
    /** Executes a list until END, stall or an error. */
    private void run(DisplayList l) {
        long start = System.nanoTime();
        try { runList(l); } finally { nanos += System.nanoTime() - start; }
    }
    private void runList(DisplayList l) {
        int budget = 5_000_000;
        while (l.state == DRAWING && budget-- > 0) {
            if (l.stall != 0 && l.pc == l.stall) { l.state = STALL; return; }
            int word = m.read32(l.pc), cmd = word >>> 24, data = word & 0x00ffffff; int at = l.pc; l.pc += 4; commands++;
            switch (cmd) {
                case 0x08: l.pc = address(l, data & ~3); break;                                        // JUMP
                case 0x0a: if (l.depth >= 30) throw new Kernel.Stop("GE call stack overflow"); l.stack[l.depth++] = l.pc; l.stack[l.depth++] = l.offset; l.pc = address(l, data & ~3); break; // CALL
                case 0x0b: if (l.depth < 2) throw new Kernel.Stop("GE RET without CALL at " + Integer.toHexString(at)); l.offset = l.stack[--l.depth]; l.pc = l.stack[--l.depth]; break; // RET
                case 0x0c: // END
                    if (l.finished) { l.state = DONE; finishedLists++; notify(l, false, registers[0x0f] & 0xffff); }
                    else if (l.signal) { l.signal = false; notify(l, true, registers[0x0e] & 0xffff); } // execution continues after a signal
                    else l.state = DONE;
                    break;
                case 0x0e: registers[0x0e] = word; l.signal = true; break; // SIGNAL (acted on at END)
                case 0x0f: registers[0x0f] = word; l.finished = true; break; // FINISH
                case 0x13: l.offset = data << 8; break;        // OFFSET
                case 0x14: l.offset = at; break;              // ORIGIN
                case 0x04: case 0x05: case 0x06: primitives++; registers[cmd] = word; if (renderer != null) renderer.command(this, cmd, data, at); break;
                default: registers[cmd] = word; if (renderer != null) renderer.command(this, cmd, data, at); break;
            }
        }
        if (budget <= 0) throw new Kernel.Stop(String.format(Locale.ROOT, "GE display list %d did not terminate (pc=%08x)", l.id, l.pc));
        if (renderer != null) renderer.flush(); // results are visible to the CPU whenever list processing returns
    }
    /** Runs the signal or finish callback registered for the list, in interrupt context once the current syscall returns. */
    private void notify(DisplayList l, boolean signal, int value) {
        int[] cb = callbacks.get(l.callbackId); if (cb == null) return;
        int function = signal ? cb[0] : cb[2], argument = signal ? cb[1] : cb[3];
        if (function != 0) k.interrupt(function, cb[4], new int[]{value, argument}, r -> { });
    }
    /** Framebuffer address from the frame buffer registers (for the renderer). */
    public int frameBuffer() { return (registers[0x9c] & 0xffffff) | ((registers[0x9d] & 0xff0000) << 8); }
}
