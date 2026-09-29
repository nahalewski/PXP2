package org.pxp2.core;

import java.util.*;
import java.util.function.IntConsumer;

/**
 * Original high-level (HLE) PSP kernel for PXP2. Guest modules run on the interpreter; their
 * imports of kernel libraries are patched to {@code jr ra; syscall n} stubs that dispatch here.
 * Imports with no implementation stop execution with an explicit report rather than returning
 * invented results. Time is virtual: one instruction is one cycle of a 222 MHz CPU, and idle
 * periods skip directly to the next timer event.
 */
public final class Kernel implements Cpu.Syscalls {
    @FunctionalInterface public interface Hle { int call(Kernel k); }
    /** Raised to stop emulation with a reason. */
    public static final class Stop extends RuntimeException { public Stop(String message) { super(message); } }

    public static final int ERROR = 0x80020001, ILLEGAL_ARGUMENT = 0x800200d2, ILLEGAL_ADDR = 0x800200d3, UNKNOWN_UID = 0x800200cb,
        NO_MEMORY = 0x80020190, ILLEGAL_PRIORITY = 0x80020193, UNKNOWN_THID = 0x80020198, UNKNOWN_SEMID = 0x80020199,
        UNKNOWN_EVFID = 0x8002019a, UNKNOWN_VPLID = 0x8002019c, UNKNOWN_FPLID = 0x8002019d, UNKNOWN_CBID = 0x800201a1,
        DORMANT = 0x800201a2, NOT_DORMANT = 0x800201a4, NOT_WAIT = 0x800201a6, CAN_NOT_WAIT = 0x800201a7, WAIT_TIMEOUT = 0x800201a8,
        WAIT_CANCEL = 0x800201a9, RELEASE_WAIT = 0x800201aa, SEMA_ZERO = 0x800201ad, SEMA_OVF = 0x800201ae, EVF_COND = 0x800201af,
        EVF_MULTI = 0x800201b0, EVF_ILPAT = 0x800201b1, WAIT_DELETE = 0x800201b5, ILLEGAL_MEMBLOCK = 0x800201b6;
    public static final long CPU_HZ = 222_000_000L;

    // ---- function table ---------------------------------------------------------------------
    public static final class Function {
        public final String library, name; public final int nid; public final Hle handler; public long calls;
        Function(String library, int nid, String name, Hle handler) { this.library = library; this.nid = nid; this.name = name; this.handler = handler; }
    }
    /** A syscall slot: an imported (library, nid) and its HLE implementation, if any. */
    static final class Slot { final String library; final int nid; Function function; Slot(String l, int n) { library = l; nid = n; } }
    private final Map<String, Function> functions = new HashMap<>();
    private final Map<String, Integer> slotCodes = new HashMap<>();
    private final List<Slot> slots = new ArrayList<>();
    private static final int CODE_THREAD_EXIT = 0xfff00, CODE_CALL_RETURN = 0xfff01;

    public final Memory memory;
    public final Cpu cpu;
    public final SysMem sysmem;
    /**
     * Hardware model reported by sceKernelGetModel, vshKernelGetModel and kuKernelGetModel: 0 = PSP-1000 (01g).
     * It stays 0 at every RAM size, because the installed firmware is the 01g image (see psp.md "System memory size").
     */
    public int model;
    public HleIo io; public HleModules modules; public HleThreads threadman;
    public final List<java.util.function.BiConsumer<GuestThread, Integer>> exitListeners = new ArrayList<>();
    private final Map<Integer, Object> objects = new HashMap<>();
    private int nextUid = 0x00100001;
    public final List<String> log = new ArrayList<>();
    public final java.util.LinkedList<String> callTrace = new java.util.LinkedList<>();
    /** Diagnostics: HLE calls whose name matches are printed as they happen (with the result). */
    public java.util.regex.Pattern liveTrace;
    private final int[] pcRing = new int[64]; private int pcRingAt;
    public int trampolines;

    /** A PSP-1000 machine (32 MiB RAM). */
    public Kernel() { this(Memory.SIZE); }
    /** @param ramBytes main RAM size: 32, 64 or 128 MiB (see {@link Memory#Memory(int)}) */
    public Kernel(int ramBytes) {
        memory = new Memory(ramBytes); cpu = new Cpu(memory, 0); sysmem = new SysMem(this);
        cpu.syscalls = this;
        trampolines = sysmem.allocate(1, "PXP2 kernel trampolines", SysMem.LOW, 256, 0);
        // thread exit: syscall; nop.  guest-call return: syscall; nop.
        memory.write32(trampolines, (CODE_THREAD_EXIT << 6) | 12); memory.write32(trampolines + 4, 0);
        memory.write32(trampolines + 8, (CODE_CALL_RETURN << 6) | 12); memory.write32(trampolines + 12, 0);
        interruptStack = sysmem.allocate(1, "PXP2 interrupt stack", SysMem.HIGH, 0x4000, 0) + 0x4000 - 64;
    }
    public int threadExitAddress() { return trampolines; }

    // ---- registration and dispatch ------------------------------------------------------------
    public void register(String library, int nid, String name, Hle handler) {
        Function f = new Function(library, nid, name, handler);
        if (functions.put(key(library, nid), f) != null) throw new IllegalStateException("Duplicate HLE " + name);
    }
    public static String key(String library, int nid) { return library + ":" + String.format(Locale.ROOT, "%08x", nid); }
    public Function function(String library, int nid) { return functions.get(key(library, nid)); }
    /** Returns the syscall code for an import, creating a slot when needed. */
    public int syscallCode(String library, int nid) {
        String k = key(library, nid); Integer code = slotCodes.get(k);
        if (code != null) return code;
        Slot slot = new Slot(library, nid); slot.function = functions.get(k);
        if (slot.function == null && importResolver != null) slot.function = importResolver.apply(library, nid); // plugin support (HleSystemCtrl)
        slots.add(slot); code = slots.size() - 1; slotCodes.put(k, code); return code;
    }
    /**
     * Plugin support (see HleSystemCtrl): consulted when an import has no HLE function of its own, e.g. a kernel-mode
     * library name (ThreadManForKernel, sceCtrl_driver) served by its user-mode twin. Returns null to keep the default.
     */
    public java.util.function.BiFunction<String, Integer, Function> importResolver;
    /** Creates an HLE function object without registering it (for {@link #importResolver}). */
    public static Function function(String library, int nid, String name, Hle handler) { return new Function(library, nid, name, handler); }
    public String describe(String library, int nid) { Function f = function(library, nid); return key(library, nid) + (f != null ? " (" + f.name + ")" : ""); }

    private boolean returnHandled, inSyscall;
    public int arg(int i) { return i < 8 ? cpu.register(4 + i) : memory.read32(cpu.register(29) + (i - 8) * 4); }
    public void returnValue64(long value) { cpu.setRegister(2, (int)value); cpu.setRegister(3, (int)(value >>> 32)); returnHandled = true; }
    public long arg64(int i) { return Integer.toUnsignedLong(arg(i)) | ((long)arg(i + 1) << 32); } // i must be even
    public String string(int address) { return memory.string(address, 1024); }

    @Override public void syscall(Cpu c, int code) {
        if (code == CODE_THREAD_EXIT) { post(() -> exitThread(current, cpu.register(2), false)); return; }
        if (code == CODE_CALL_RETURN) { int value = cpu.register(2); post(() -> guestCallReturn(value)); return; }
        if (code < 0 || code >= slots.size()) throw new Stop(String.format("Unknown syscall code %05x at %08x", code, c.pc()));
        Slot slot = slots.get(code);
        // After a kernel-mode homebrew module was refused, a missing import is almost always one of its exports: say so first.
        if (slot.function == null && modules != null && modules.unsupported() != null) throw new Stop(modules.unsupported() + ". Stopped at its call to " + describe(slot.library, slot.nid) + ".");
        if (slot.function == null) throw new Stop("Unimplemented import " + describe(slot.library, slot.nid) + String.format(Locale.ROOT,
            " called from %08x args=%08x,%08x,%08x,%08x,%08x,%08x", c.register(31) - 8, arg(0), arg(1), arg(2), arg(3), arg(4), arg(5)) + pointerPreview());
        Function f = slot.function; f.calls++;
        if (callTrace.size() >= 4000) callTrace.remove(0);
        callTrace.add(f.name + String.format(Locale.ROOT, "(%08x,%08x,%08x,%08x) ra=%08x t=%s", arg(0), arg(1), arg(2), arg(3), c.register(31), current == null ? "-" : current.name));
        returnHandled = false;
        int result; inSyscall = true;
        try { result = f.handler.call(this); } finally { inSyscall = false; }
        if (!returnHandled) cpu.setRegister(2, result);
        if (liveTrace != null && liveTrace.matcher(f.name).find()) System.out.println(String.format(Locale.ROOT, "[%9d us] ", now()) + callTrace.getLast() + String.format(Locale.ROOT, " -> %08x", returnHandled ? cpu.register(2) : result));
        int ra = c.register(31);
        if (traceHigh != 0 && Integer.compareUnsigned(ra - traceLow, traceHigh - traceLow) < 0) log(String.format(Locale.ROOT, "call %s(%08x,%08x,%08x,%08x) -> %08x ra=%08x", f.name, arg(0), arg(1), arg(2), arg(3), c.register(2), ra));
    }

    /** Shows printable strings behind pointer arguments, to help identify unknown imports. */
    private String pointerPreview() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int a = arg(i); if (!memory.valid(a, 4)) continue;
            StringBuilder t = new StringBuilder(); int[] words = new int[4];
            for (int j = 0; j < 48 && memory.valid(a + j, 1); j++) { int ch = memory.read8(a + j); if (ch == 0) break; if (ch < 32 || ch > 126) { t.setLength(0); break; } t.append((char)ch); }
            for (int j = 0; j < 4; j++) words[j] = (a & 3) == 0 && memory.valid(a + j * 4, 4) ? memory.read32(a + j * 4) : 0;
            out.append(String.format(Locale.ROOT, "%n  arg%d -> %s[%08x %08x %08x %08x]", i, t.length() > 2 ? "\"" + t + "\" " : "", words[0], words[1], words[2], words[3]));
        }
        return out.toString();
    }

    // ---- objects -------------------------------------------------------------------------------
    public int newUid(Object object) { int uid = nextUid; nextUid += 2; objects.put(uid, object); return uid; }
    public <T> T object(int uid, Class<T> type) { Object o = objects.get(uid); return type.isInstance(o) ? type.cast(o) : null; }
    public void deleteUid(int uid) { objects.remove(uid); }
    public <T> List<T> objectsOf(Class<T> type) { List<T> out = new ArrayList<>(); for (Object o : objects.values()) if (type.isInstance(o)) out.add(type.cast(o)); return out; }
    public void log(String message) { if (log.size() < 50000) log.add(String.format(Locale.ROOT, "[%9d us] %s", now(), message)); }

    // ---- time and events -----------------------------------------------------------------------
    private long idleMicros;
    public long now() { return cpu.cycles * 1_000_000L / CPU_HZ + idleMicros; }
    private static final class Event implements Comparable<Event> {
        final long time, seq; final Runnable action; boolean cancelled;
        Event(long time, long seq, Runnable action) { this.time = time; this.seq = seq; this.action = action; }
        public int compareTo(Event o) { return time != o.time ? Long.compare(time, o.time) : Long.compare(seq, o.seq); }
    }
    private final PriorityQueue<Event> events = new PriorityQueue<>();
    private long eventSeq, nextEventCycle = Long.MAX_VALUE;
    public Object schedule(long atMicros, Runnable action) {
        Event e = new Event(atMicros, eventSeq++, action); events.add(e); updateNextEvent(); return e;
    }
    public void cancel(Object event) { if (event instanceof Event) ((Event)event).cancelled = true; }
    private void updateNextEvent() {
        while (!events.isEmpty() && events.peek().cancelled) events.poll();
        if (events.isEmpty()) { nextEventCycle = Long.MAX_VALUE; return; }
        long t = events.peek().time - idleMicros;
        nextEventCycle = t <= 0 ? 0 : (t * CPU_HZ + 999_999) / 1_000_000L;
    }
    private void processEvents() {
        long t = now();
        while (!events.isEmpty() && (events.peek().cancelled || events.peek().time <= t)) {
            Event e = events.poll(); if (!e.cancelled) e.action.run();
        }
        updateNextEvent();
    }

    // ---- threads -------------------------------------------------------------------------------
    public enum State { RUNNING, READY, WAITING, SUSPENDED, DORMANT, DEAD }
    public static final class Wait {
        public final String type; public final int id; public Object data; Object timeout; int timeoutAddress; boolean callbacks;
        public Wait(String type, int id) { this.type = type; this.id = id; }
    }
    public final class GuestThread {
        public final int uid; public final String name; public int entry, priority, initialPriority, attributes, stackSize, stack, gp, exitStatus;
        public final Cpu.Context context = new Cpu.Context();
        public State state = State.DORMANT; public Wait wait; public int wakeupCount; long readySeq; public int module;
        final Deque<Frame> frames = new ArrayDeque<>();
        final List<Callback> pendingCallbacks = new ArrayList<>();
        GuestThread(String name) { this.name = name; uid = newUid(this); }
        public String toString() { return String.format(Locale.ROOT, "%s[%x pri=%x %s%s pc=%08x ra=%08x]", name, uid, priority, state, wait == null ? "" : ":" + wait.type + (wait.id != 0 ? "#" + Integer.toHexString(wait.id) : ""), context.pc, context.r[31]); }
    }
    /** Saved state while a thread runs a callback. */
    static final class Frame { final Cpu.Context context = new Cpu.Context(); IntConsumer done; }
    public static final class Callback { public final int uid, owner, entry, argument; public final String name; public int notifyCount, notifyArg; Callback(int uid, String name, int owner, int entry, int argument) { this.uid = uid; this.name = name; this.owner = owner; this.entry = entry; this.argument = argument; } }

    public GuestThread current;
    private long readyCounter;
    private boolean reschedule;
    private final List<Runnable> postStep = new ArrayList<>();
    public void post(Runnable action) { postStep.add(action); }
    public List<GuestThread> threads() { return objectsOf(GuestThread.class); }

    private static final int K0_BLOCK = 0x100;
    public GuestThread createThread(String name, int entry, int priority, int stackSize, int attributes, int gp) {
        GuestThread t = new GuestThread(name == null ? "thread" : name);
        t.entry = entry; t.priority = t.initialPriority = priority; t.attributes = attributes; t.gp = gp;
        t.stackSize = Math.max(0x200, (stackSize + 0xff) & ~0xff);
        int partition = (attributes & 0x80000000) != 0 ? 2 : 1; // PSP_THREAD_ATTR_USER stacks live in user memory
        t.stack = sysmem.allocate(partition, "stack:" + t.name, SysMem.HIGH, t.stackSize + K0_BLOCK, 0); // + k0 block above the stack
        if (t.stack == 0) { deleteUid(t.uid); return null; }
        if ((attributes & 0x00100000) == 0) memory.fill(t.stack, 0xff, t.stackSize); // unless NO_FILLSTACK, stacks are filled for stack checks
        if ((attributes & 0x00200000) != 0) memory.fill(t.stack, 0, t.stackSize);   // CLEAR_STACK
        return t;
    }
    public void startThread(GuestThread t, int argSize, int argPointer) {
        Cpu.Context c = t.context; Arrays.fill(c.r, 0); Arrays.fill(c.f, 0); c.vfpu.reset(); c.hi = c.lo = 0; c.fcr31 = 0;
        // Each thread has a 256-byte k0 block (k0 points at it; +0xc0 thread id, +0xc8 stack base, +0xf8/+0xfc -1), as
        // on hardware. Game start-up code (e.g. DBZ Shin Budokai's crt0) stores its own data at 4(k0), so k0 must be a
        // writable per-thread block, not the uid. The firmware carves it from the top of the stack; PXP2 allocates it
        // just above the stack so the stack layout VSH threads were tuned against (sp = top - 64) is unchanged.
        int k0 = t.stack + t.stackSize, sp = t.stack + t.stackSize - 64;
        memory.fill(k0, 0, K0_BLOCK); memory.write32(k0 + 0xc0, t.uid); memory.write32(k0 + 0xc8, t.stack); memory.write32(k0 + 0xf8, -1); memory.write32(k0 + 0xfc, -1);
        // Thread arguments are copied to the top of the new thread's stack.
        if (argSize > 0 && argPointer != 0) { sp = (sp - argSize) & ~15; memory.copy(sp, argPointer, argSize); c.r[5] = sp; sp -= 64; }
        c.r[4] = argSize; if (argSize == 0 || argPointer == 0) c.r[5] = argPointer;
        c.r[29] = sp; c.r[28] = t.gp; c.r[31] = trampolines; c.r[26] = k0;
        c.pc = t.entry; c.next = t.entry + 4; c.delay = false;
        t.priority = t.initialPriority; t.wakeupCount = 0; t.wait = null; t.frames.clear();
        makeReady(t);
    }
    public void makeReady(GuestThread t) { t.state = State.READY; t.readySeq = readyCounter++; reschedule = true; }
    /** Blocks the current thread after the current syscall completes. */
    public void waitCurrent(Wait wait, int timeoutAddress, boolean callbacks) {
        GuestThread t = current; if (t == null) throw new Stop("Blocking call outside a thread: " + wait.type);
        t.wait = wait; t.state = State.WAITING; wait.callbacks = callbacks; wait.timeoutAddress = timeoutAddress; reschedule = true;
        if (timeoutAddress != 0) {
            long delay = Integer.toUnsignedLong(memory.read32(timeoutAddress)); long due = now() + delay;
            wait.timeout = schedule(due, () -> { if (t.wait == wait) { memory.write32(timeoutAddress, 0); onTimeout(t, wait); wake(t, WAIT_TIMEOUT); } });
            dueTimes.put(wait, due);
        }
    }
    private final Map<Wait, Long> dueTimes = new IdentityHashMap<>();
    /** Hook for sync objects to drop timed-out waiters from their queues. */
    public final List<java.util.function.BiConsumer<GuestThread, Wait>> timeoutListeners = new ArrayList<>();
    private void onTimeout(GuestThread t, Wait w) { for (java.util.function.BiConsumer<GuestThread, Wait> l : timeoutListeners) l.accept(t, w); }
    /** Ends a thread's wait with the given return value. */
    public void wake(GuestThread t, int result) {
        Wait w = t.wait; if (w == null) return;
        if (w.timeout != null) {
            cancel(w.timeout); Long due = dueTimes.remove(w);
            if (due != null && result != WAIT_TIMEOUT) memory.write32(w.timeoutAddress, (int)Math.max(0, due - now()));
        }
        t.wait = null; setReturn(t, result);
        if (t.state == State.WAITING) makeReady(t);
    }
    /** Sets a thread's syscall return value wherever its live registers currently are. */
    public void setReturn(GuestThread t, int value) {
        if (!t.frames.isEmpty()) t.frames.peekLast().context.r[2] = value;
        else if (t == current) { if (inInterrupt) interruptSaved.r[2] = value; else cpu.setRegister(2, value); }
        else t.context.r[2] = value;
    }
    public void exitThread(GuestThread t, int status, boolean delete) {
        if (t == null) return;
        t.exitStatus = status; t.state = State.DORMANT; t.wait = null;
        log("Thread " + t.name + " exited with " + Integer.toHexString(status));
        for (GuestThread other : threads()) if (other.wait != null && other.wait.type.equals("ThreadEnd") && other.wait.id == t.uid) wake(other, status);
        for (java.util.function.BiConsumer<GuestThread, Integer> l : exitListeners) l.accept(t, status);
        if (delete) deleteThread(t);
        if (t == current) { current = null; }
        reschedule = true;
    }
    public void deleteThread(GuestThread t) { t.state = State.DEAD; sysmem.free(t.stack); deleteUid(t.uid); }
    public void yieldCurrent() { if (current != null) { current.readySeq = readyCounter++; reschedule = true; } }
    public void requestReschedule() { reschedule = true; }

    private void schedule() {
        reschedule = false;
        if (inInterrupt) return;
        GuestThread best = null;
        for (GuestThread t : threads()) {
            if ((t.state == State.READY || t.state == State.RUNNING) && (best == null || t.priority < best.priority || (t.priority == best.priority && t.readySeq < best.readySeq))) best = t;
        }
        // Waiting threads with callbacks pending run the callback, then resume waiting.
        for (GuestThread t : threads()) if (t.state == State.WAITING && t.wait != null && t.wait.callbacks && !t.pendingCallbacks.isEmpty()
            && (best == null || t.priority < best.priority)) {
            // A thread that has just entered its wait (e.g. sceDisplayWaitVblankStartCB) is still current: its live
            // registers are in the CPU, so save them before building the callback frame and load the callback after.
            if (t == current) { cpu.save(t.context); runCallbacks(t); cpu.load(t.context); t.state = State.RUNNING; return; }
            runCallbacks(t); best = t; break;
        }
        if (best == current) { if (current != null) current.state = State.RUNNING; return; }
        if (current != null) { cpu.save(current.context); if (current.state == State.RUNNING) current.state = State.READY; }
        current = best;
        if (best != null) { cpu.load(best.context); best.state = State.RUNNING; }
    }

    // ---- guest calls (callbacks and interrupt handlers) -------------------------------------------
    private boolean inInterrupt; private final Cpu.Context interruptSaved = new Cpu.Context();
    private int interruptStack; private IntConsumer interruptDone; private GuestThread interruptedThread;
    private final Deque<Runnable> pendingInterrupts = new ArrayDeque<>();
    public boolean inInterrupt() { return inInterrupt; }
    /** Runs a guest function in interrupt context, preempting the current thread. */
    public void interrupt(int address, int gp, int[] args, IntConsumer done) {
        Runnable start = () -> {
            inInterrupt = true; interruptedThread = current; cpu.save(interruptSaved); interruptDone = done;
            Cpu.Context c = new Cpu.Context(); c.r[29] = interruptStack; c.r[28] = gp; c.r[31] = trampolines + 8;
            for (int i = 0; i < args.length && i < 8; i++) c.r[4 + i] = args[i];
            c.pc = address; c.next = address + 4; cpu.load(c);
        };
        // Raised from inside a syscall (e.g. a GE finish callback during sceGeListEnQueue), the handler must start once
        // the syscall instruction has completed: the CPU is still mid-step, so switching context now would skip the
        // handler's first instruction and, on return, execute the syscall again.
        if (inSyscall) { post(() -> { if (inInterrupt) pendingInterrupts.add(start); else start.run(); }); return; }
        if (inInterrupt) pendingInterrupts.add(start); else start.run();
    }
    private void runCallbacks(GuestThread t) {
        Callback cb = t.pendingCallbacks.remove(0);
        Frame frame = new Frame(); copy(t.context, frame.context);
        int count = cb.notifyCount, arg = cb.notifyArg; cb.notifyCount = 0; cb.notifyArg = 0;
        frame.done = result -> { if (result != 0) { deleteUid(cb.uid); } };
        t.frames.push(frame);
        Cpu.Context c = t.context; c.r[4] = count; c.r[5] = arg; c.r[6] = cb.argument; c.r[31] = trampolines + 8;
        c.r[29] = (c.r[29] - 0x40) & ~15; c.pc = cb.entry; c.next = cb.entry + 4; c.delay = false;
        t.state = State.READY;
    }
    private static void copy(Cpu.Context from, Cpu.Context to) {
        System.arraycopy(from.r, 0, to.r, 0, 32); System.arraycopy(from.f, 0, to.f, 0, 32);
        to.pc = from.pc; to.next = from.next; to.hi = from.hi; to.lo = from.lo; to.fcr31 = from.fcr31; to.delay = from.delay; to.vfpu.copyFrom(from.vfpu);
    }
    private void guestCallReturn(int value) {
        if (inInterrupt) {
            inInterrupt = false; cpu.load(interruptSaved); current = interruptedThread;
            IntConsumer done = interruptDone; interruptDone = null; if (done != null) done.accept(value);
            if (!pendingInterrupts.isEmpty()) pendingInterrupts.poll().run();
            reschedule = true; return;
        }
        GuestThread t = current;
        if (t == null || t.frames.isEmpty()) throw new Stop(String.format("Guest call return outside a call at %08x", cpu.pc()));
        Frame frame = t.frames.pop(); frame.done.accept(value);
        cpu.load(frame.context);
        // Resume the interrupted wait, or keep running if the wait ended meanwhile.
        if (t.wait != null) { cpu.save(t.context); t.state = State.WAITING; current = null; }
        reschedule = true;
    }
    /** Queues a callback notification; returns false if the callback is unknown. */
    public boolean notifyCallback(int uid, int argument) {
        Callback cb = object(uid, Callback.class); if (cb == null) return false;
        cb.notifyCount++; cb.notifyArg = argument;
        GuestThread owner = object(cb.owner, GuestThread.class);
        if (owner != null && !owner.pendingCallbacks.contains(cb)) owner.pendingCallbacks.add(cb);
        reschedule = true; return true;
    }
    public Callback createCallback(String name, int entry, int argument) {
        int uid = nextUid; Callback cb = new Callback(uid, name, current == null ? 0 : current.uid, entry, argument); newUid(cb); return cb;
    }
    /** sceKernelCheckCallback: run pending callbacks of the current thread; returns whether any ran. */
    public boolean checkCallbacks() {
        GuestThread t = current; if (t == null || t.pendingCallbacks.isEmpty()) return false;
        // After this syscall returns 1, the thread runs its callbacks before continuing.
        post(() -> { if (current != t) return; cpu.save(t.context); t.context.r[2] = 1; runCallbacks(t); cpu.load(t.context); t.state = State.RUNNING; });
        return true;
    }

    // ---- main loop -----------------------------------------------------------------------------
    public volatile boolean stopRequested;
    /** Plugin support: returns true when it has ended the faulting thread and emulation may continue (see Plugins). */
    public java.util.function.BiPredicate<GuestThread, String> faultHandler;
    /** Diagnostic PC watchpoints: each hit is written to the kernel log. */
    public final Set<Integer> watch = new HashSet<>();
    /** Diagnostic: log every HLE call whose return address lies in [traceLow, traceHigh). */
    public int traceLow, traceHigh;
    /** Memory words printed with every watchpoint hit. */
    public final List<Integer> watchWords = new ArrayList<>();
    private String watchWordValues() { StringBuilder b = new StringBuilder(); for (int w : watchWords) b.append(String.format(Locale.ROOT, "[%08x]=%08x ", w, memory.read32(w))); return b.toString(); }
    public long instructions() { return cpu.cycles; }
    /** Runs until the budget is exhausted, a stop condition occurs or {@code done} is true. */
    public String run(long budget, java.util.function.BooleanSupplier done) {
        long limit = cpu.cycles + budget;
        try {
            while (true) {
                if (done != null && done.getAsBoolean()) return "Completed";
                if (stopRequested) { stopRequested = false; return "Stopped by host"; }
                if (Thread.currentThread().isInterrupted()) return "Cancelled";
                if (reschedule) schedule();
                if (current == null && !inInterrupt) {
                    if (events.isEmpty()) return "All threads are waiting with no pending timer (guest deadlock)\n" + threadReport();
                    Event e = events.peek(); long skip = e.time - now(); if (skip > 0) idleMicros += skip;
                    processEvents(); if (reschedule) schedule(); continue;
                }
                if (cpu.cycles >= limit) return "Instruction budget reached";
                if (done == null && watch.isEmpty()) {
                    // Fast inner loop: the checks above can only change through a syscall, a posted action or an
                    // event, so run plain instructions back to back until one of those happens (or a batch ends).
                    long end = Math.min(limit, cpu.cycles + 4096), traps = cpu.traps;
                    final Cpu c = cpu; final int[] ring = pcRing;
                    do {
                        ring[pcRingAt++ & 63] = c.pc();
                        c.step();
                        boolean posted = !postStep.isEmpty();
                        if (posted) { List<Runnable> actions = new ArrayList<>(postStep); postStep.clear(); for (Runnable r : actions) r.run(); }
                        if (c.cycles >= nextEventCycle) { processEvents(); break; }
                        if (posted) break;
                    } while (c.traps == traps && c.cycles < end);
                    continue;
                }
                pcRing[pcRingAt++ & 63] = cpu.pc();
                if (!watch.isEmpty() && watch.contains(cpu.pc())) log(String.format(Locale.ROOT, "watch %08x ra=%08x a0=%08x a1=%08x a2=%08x a3=%08x v0=%08x thread=%s", cpu.pc(), cpu.register(31), cpu.register(4), cpu.register(5), cpu.register(6), cpu.register(7), cpu.register(2), current == null ? "-" : current.name) + (watchWords.isEmpty() ? "" : " " + watchWordValues()));
                cpu.step();
                if (!postStep.isEmpty()) { List<Runnable> actions = new ArrayList<>(postStep); postStep.clear(); for (Runnable r : actions) r.run(); }
                if (cpu.cycles >= nextEventCycle) processEvents();
            }
        } catch (Stop | IllegalStateException | IllegalArgumentException e) {
            // Plugin support (see Plugins): a fault on a thread owned by a SEPLUGINS module ends only that thread.
            if (faultHandler != null && current != null && !inInterrupt && faultHandler.test(current, e.getMessage())) return run(Math.max(1, limit - cpu.cycles), done);
            return String.format(Locale.ROOT, "%s\nPC=%08x RA=%08x SP=%08x thread=%s", e.getMessage(), cpu.pc(), cpu.register(31), cpu.register(29), current == null ? (inInterrupt ? "interrupt" : "-") : current.name);
        }
    }
    public String recentPcs() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 64; i++) { int pc = pcRing[(pcRingAt + i) & 63]; if (pc == 0) continue; s.append(String.format(Locale.ROOT, "%08x %08x\n", pc, memory.valid(pc, 4) ? memory.read32(pc) : 0)); }
        return s.toString();
    }
    public String threadReport() { StringBuilder s = new StringBuilder("Threads:\n"); for (GuestThread t : threads()) s.append("  ").append(t).append('\n'); return s.toString(); }
    public String callSummary() {
        List<Function> used = new ArrayList<>(); for (Function f : functions.values()) if (f.calls > 0) used.add(f);
        used.sort((a, b) -> Long.compare(b.calls, a.calls));
        StringBuilder s = new StringBuilder(); for (Function f : used) s.append(String.format(Locale.ROOT, "  %6d %s\n", f.calls, f.name)); return s.toString();
    }
    public int implementedFunctions() { return functions.size(); }
}
