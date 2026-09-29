package org.pxp2.core;

import java.util.*;
import org.pxp2.core.Kernel.GuestThread;
import org.pxp2.core.Kernel.Wait;

/**
 * Original ThreadManForUser, Kernel_Library and InterruptManager implementation: threads,
 * semaphores, event flags, mutexes, lightweight mutexes, fixed/variable pools, callbacks,
 * delays, clocks and alarms. Wait queues drop entries lazily once a thread's wait has ended.
 */
public final class HleThreads {
    public static final int MUTEX_LOCKED = 0x800201c4, MUTEX_UNLOCKED = 0x800201c5, MUTEX_NOT_FOUND = 0x800201c3,
        LWMUTEX_NOT_FOUND = 0x800201ca, LWMUTEX_LOCKED = 0x800201cb, LWMUTEX_UNLOCKED = 0x800201cc, UNKNOWN_ALMID = 0x8002019f;
    private final Kernel k; private final Memory m;

    /** FIFO or priority ordered queue of (thread, wait) pairs. */
    static final class Queue {
        final boolean priority; final List<Object[]> entries = new ArrayList<>();
        Queue(int attributes) { priority = (attributes & 0x100) != 0; }
        void add(GuestThread t, Wait w) { entries.add(new Object[]{t, w}); }
        void prune() { entries.removeIf(e -> ((GuestThread)e[0]).wait != e[1]); }
        List<GuestThread> ordered() {
            prune(); List<GuestThread> out = new ArrayList<>(); for (Object[] e : entries) out.add((GuestThread)e[0]);
            if (priority) out.sort(Comparator.comparingInt(t -> t.priority)); return out;
        }
        int size() { prune(); return entries.size(); }
    }
    static final class Semaphore { String name; int attr, init, count, max, uid; Queue queue; }
    static final class EventFlag { String name; int attr, init, pattern, uid; Queue queue; }
    static final class Mutex { String name; int attr, init, count, owner, uid; Queue queue; }
    static final class LwMutex { String name; int attr, workarea, uid; Queue queue; }
    static final class Fpl { String name; int attr, blockSize, count, base, uid; boolean[] used; Queue queue; }
    static final class Vpl { String name; int attr, size, base, uid; TreeMap<Integer, Integer> allocated = new TreeMap<>(); Queue queue; }
    static final class Alarm { int uid, handler, argument, gp; Object event; }
    static final class ThreadEventHandler { int uid; }
    static final class SubInterrupt { int interrupt, sub, handler, argument, gp; boolean enabled; }
    public final List<SubInterrupt> subInterrupts = new ArrayList<>();
    private boolean interruptsEnabled = true;

    public HleThreads(Kernel k) { this.k = k; this.m = k.memory; registerThreads(); registerSync(); registerPools(); registerMbx(); registerVTimers(); registerTime(); registerKernelLibrary(); registerInterrupts(); }

    private GuestThread thread(int uid) { return uid == 0 ? k.current : k.object(uid, GuestThread.class); }
    private String name(int address) { String s = k.string(address); return s == null ? "" : (s.length() > 31 ? s.substring(0, 31) : s); }
    private void wait(String type, int id, Queue q, Object data, int timeout, boolean cb) {
        Wait w = new Wait(type, id); w.data = data; if (q != null) q.add(k.current, w); k.waitCurrent(w, timeout, cb);
    }

    // ---- threads ------------------------------------------------------------------------------
    private void registerThreads() {
        String lib = "ThreadManForUser";
        k.register(lib, 0x446d8de6, "sceKernelCreateThread", c -> {
            int entry = c.arg(1), priority = c.arg(2), stack = c.arg(3), attr = c.arg(4);
            if (priority < 1 || priority > 126) return Kernel.ILLEGAL_PRIORITY;
            if (entry == 0 || !m.valid(entry, 4)) return 0x80020192; // ILLEGAL_ENTRY
            if (stack < 0x200) return 0x800201bc; // ILLEGAL_STACK_SIZE
            GuestThread t = k.createThread(name(c.arg(0)), entry, priority, stack, attr | 0x80000000, c.cpu.register(28));
            if (t == null) return Kernel.NO_MEMORY;
            if (k.current != null) t.module = k.current.module;
            return t.uid;
        });
        k.register(lib, 0xf475845d, "sceKernelStartThread", c -> {
            GuestThread t = k.object(c.arg(0), GuestThread.class); if (t == null) return Kernel.UNKNOWN_THID;
            if (t.state != Kernel.State.DORMANT) return Kernel.NOT_DORMANT;
            k.startThread(t, c.arg(1), c.arg(2)); if (k.current != null && t.priority < k.current.priority) k.yieldCurrent(); return 0;
        });
        k.register(lib, 0xaa73c935, "sceKernelExitThread", c -> { GuestThread t = k.current; int s = c.arg(0); k.post(() -> k.exitThread(t, s, false)); return 0; });
        k.register(lib, 0x809ce29b, "sceKernelExitDeleteThread", c -> { GuestThread t = k.current; int s = c.arg(0); k.post(() -> k.exitThread(t, s, true)); return 0; });
        k.register(lib, 0x616403ba, "sceKernelTerminateThread", c -> terminate(c.arg(0), false));
        k.register(lib, 0x383f7bcc, "sceKernelTerminateDeleteThread", c -> terminate(c.arg(0), true));
        k.register(lib, 0x9fa03cd3, "sceKernelDeleteThread", c -> {
            GuestThread t = k.object(c.arg(0), GuestThread.class); if (t == null) return Kernel.UNKNOWN_THID;
            if (t.state != Kernel.State.DORMANT) return Kernel.NOT_DORMANT; k.deleteThread(t); return 0;
        });
        k.register(lib, 0x293b45b8, "sceKernelGetThreadId", c -> k.current == null ? Kernel.ERROR : k.current.uid);
        k.register(lib, 0x94aa61ee, "sceKernelGetThreadCurrentPriority", c -> k.current.priority);
        k.register(lib, 0x71bc9871, "sceKernelChangeThreadPriority", c -> {
            GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID;
            int p = c.arg(1) == 0 ? (k.current == null ? 0x20 : k.current.priority) : c.arg(1);
            if (p < 1 || p > 126) return Kernel.ILLEGAL_PRIORITY;
            t.priority = p; k.yieldCurrent(); return 0;
        });
        k.register(lib, 0xea748e31, "sceKernelChangeCurrentThreadAttr", c -> { k.current.attributes = (k.current.attributes & ~c.arg(0)) | c.arg(1); return 0; });
        k.register(lib, 0x912354a7, "sceKernelRotateThreadReadyQueue", c -> { k.yieldCurrent(); return 0; });
        k.register(lib, 0x3ad58b8c, "sceKernelSuspendDispatchThread", c -> 1);
        k.register(lib, 0x27e22ec2, "sceKernelResumeDispatchThread", c -> 0);
        k.register(lib, 0xd13bde95, "sceKernelCheckThreadStack", c -> k.current == null ? 0 : c.cpu.register(29) - k.current.stack);
        k.register(lib, 0x52089ca1, "sceKernelGetThreadStackFreeSize", c -> {
            GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID;
            int n = 0; while (n < t.stackSize && m.read8(t.stack + n) == 0xff) n++; return n;
        });
        k.register(lib, 0x3b183e26, "sceKernelGetThreadExitStatus", c -> {
            GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID;
            return t.state == Kernel.State.DORMANT ? t.exitStatus : Kernel.NOT_DORMANT;
        });
        k.register(lib, 0x17c1684e, "sceKernelReferThreadStatus", c -> {
            GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID;
            int p = c.arg(1); if (m.read32(p) < 104) return 0;
            m.fill(p + 4, 0, 100); m.writeString(p + 4, t.name, 32); m.write32(p + 36, t.attributes);
            int status; switch (t.state) { case RUNNING: status = 1; break; case READY: status = 2; break; case WAITING: status = 4; break; case SUSPENDED: status = 8; break; case DORMANT: status = 16; break; default: status = 32; }
            m.write32(p + 40, status); m.write32(p + 44, t.entry); m.write32(p + 48, t.stack); m.write32(p + 52, t.stackSize);
            m.write32(p + 56, t.gp); m.write32(p + 60, t.initialPriority); m.write32(p + 64, t.priority);
            m.write32(p + 68, t.wait == null ? 0 : 1); m.write32(p + 72, t.wait == null ? 0 : t.wait.id);
            m.write32(p + 76, t.wakeupCount); m.write32(p + 80, t.exitStatus); return 0;
        });
        k.register(lib, 0x278c0df5, "sceKernelWaitThreadEnd", c -> waitThreadEnd(c.arg(0), c.arg(1), false));
        k.register(lib, 0x840e8133, "sceKernelWaitThreadEndCB", c -> waitThreadEnd(c.arg(0), c.arg(1), true));
        k.register(lib, 0x9ace131e, "sceKernelSleepThread", c -> sleep(false));
        k.register(lib, 0x82826f70, "sceKernelSleepThreadCB", c -> sleep(true));
        k.register(lib, 0xd59ead2f, "sceKernelWakeupThread", c -> {
            GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID;
            if (t.wait != null && t.wait.type.equals("Sleep")) k.wake(t, 0); else t.wakeupCount++; return 0;
        });
        k.register(lib, 0xfccfad26, "sceKernelCancelWakeupThread", c -> { GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID; int n = t.wakeupCount; t.wakeupCount = 0; return n; });
        k.register(lib, 0x2c34e053, "sceKernelReleaseWaitThread", c -> {
            GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID; if (t.wait == null) return Kernel.NOT_WAIT;
            k.wake(t, Kernel.RELEASE_WAIT); return 0;
        });
        k.register(lib, 0x9944f31f, "sceKernelSuspendThread", c -> { GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID; if (t.state == Kernel.State.READY) t.state = Kernel.State.SUSPENDED; return 0; });
        k.register(lib, 0x75156e8f, "sceKernelResumeThread", c -> { GuestThread t = thread(c.arg(0)); if (t == null) return Kernel.UNKNOWN_THID; if (t.state == Kernel.State.SUSPENDED) k.makeReady(t); return 0; });
        k.register(lib, 0x0c106e53, "sceKernelRegisterThreadEventHandler", c -> { k.log("Thread event handler registered (events are not delivered): " + k.string(c.arg(0))); return k.newUid(new ThreadEventHandler()); });
        k.register(lib, 0x72f3c145, "sceKernelReleaseThreadEventHandler", c -> { k.deleteUid(c.arg(0)); return 0; });
        k.register(lib, 0x57cf62dd, "sceKernelGetThreadmanIdType", c -> {
            Object o = k.object(c.arg(0), Object.class);
            if (o instanceof GuestThread) return 1; if (o instanceof Semaphore) return 2; if (o instanceof EventFlag) return 3;
            if (o instanceof Vpl) return 5; if (o instanceof Fpl) return 6; if (o instanceof Kernel.Callback) return 8; if (o instanceof Alarm) return 10;
            if (o instanceof Mutex) return 12; if (o instanceof LwMutex) return 13; return Kernel.UNKNOWN_UID;
        });
        k.register(lib, 0x94416130, "sceKernelGetThreadmanIdList", c -> {
            if (c.arg(0) != 1) throw new Kernel.Stop("sceKernelGetThreadmanIdList type " + c.arg(0) + " is not implemented");
            int n = 0; for (GuestThread t : k.threads()) { if (n < c.arg(2)) m.write32(c.arg(1) + n * 4, t.uid); n++; }
            if (c.arg(3) != 0) m.write32(c.arg(3), n); return 0;
        });
        // Callbacks
        k.register(lib, 0xe81caf8f, "sceKernelCreateCallback", c -> k.createCallback(name(c.arg(0)), c.arg(1), c.arg(2)).uid);
        k.register(lib, 0xedba5844, "sceKernelDeleteCallback", c -> { if (k.object(c.arg(0), Kernel.Callback.class) == null) return Kernel.UNKNOWN_CBID; k.deleteUid(c.arg(0)); return 0; });
        k.register(lib, 0xc11ba8c4, "sceKernelNotifyCallback", c -> k.notifyCallback(c.arg(0), c.arg(1)) ? 0 : Kernel.UNKNOWN_CBID);
        k.register(lib, 0xba4051d6, "sceKernelCancelCallback", c -> { Kernel.Callback cb = k.object(c.arg(0), Kernel.Callback.class); if (cb == null) return Kernel.UNKNOWN_CBID; cb.notifyCount = 0; return 0; });
        k.register(lib, 0x2a3d44ff, "sceKernelGetCallbackCount", c -> { Kernel.Callback cb = k.object(c.arg(0), Kernel.Callback.class); return cb == null ? Kernel.UNKNOWN_CBID : cb.notifyCount; });
        k.register(lib, 0x349d6d6c, "sceKernelCheckCallback", c -> k.checkCallbacks() ? 1 : 0);
        k.register(lib, 0x730ed8bc, "sceKernelReferCallbackStatus", c -> {
            Kernel.Callback cb = k.object(c.arg(0), Kernel.Callback.class); if (cb == null) return Kernel.UNKNOWN_CBID;
            int p = c.arg(1); m.fill(p + 4, 0, 52); m.writeString(p + 4, cb.name, 32); m.write32(p + 36, cb.owner); m.write32(p + 40, cb.entry);
            m.write32(p + 44, cb.argument); m.write32(p + 48, cb.notifyCount); m.write32(p + 52, cb.notifyArg); return 0;
        });
    }
    private int terminate(int uid, boolean delete) {
        GuestThread t = k.object(uid, GuestThread.class); if (t == null) return Kernel.UNKNOWN_THID;
        if (t == k.current) return 0x800201a2;
        if (t.state == Kernel.State.DORMANT) { if (delete) k.deleteThread(t); return delete ? 0 : Kernel.DORMANT; }
        k.exitThread(t, 0x800201ac, delete); return 0;
    }
    private int waitThreadEnd(int uid, int timeout, boolean cb) {
        GuestThread t = k.object(uid, GuestThread.class); if (t == null) return Kernel.UNKNOWN_THID;
        if (t == k.current) return 0x800201a7;
        if (t.state == Kernel.State.DORMANT) return t.exitStatus;
        wait("ThreadEnd", uid, null, null, timeout, cb); return 0;
    }
    private int sleep(boolean cb) {
        GuestThread t = k.current; if (t.wakeupCount > 0) { t.wakeupCount--; return 0; }
        wait("Sleep", 0, null, null, 0, cb); return 0;
    }

    // ---- semaphores, event flags, mutexes -------------------------------------------------------
    private void registerSync() {
        String lib = "ThreadManForUser";
        k.register(lib, 0xd6da4ba1, "sceKernelCreateSema", c -> {
            Semaphore s = new Semaphore(); s.name = name(c.arg(0)); s.attr = c.arg(1); s.init = s.count = c.arg(2); s.max = c.arg(3);
            if (s.max <= 0 || s.init < 0 || s.init > s.max) return 0x800201bd; // ILLEGAL_COUNT
            s.queue = new Queue(s.attr); return s.uid = k.newUid(s);
        });
        k.register(lib, 0x28b6489c, "sceKernelDeleteSema", c -> { Semaphore s = k.object(c.arg(0), Semaphore.class); if (s == null) return Kernel.UNKNOWN_SEMID; wakeAll(s.queue, Kernel.WAIT_DELETE); k.deleteUid(s.uid); return 0; });
        k.register(lib, 0x3f53e640, "sceKernelSignalSema", c -> {
            Semaphore s = k.object(c.arg(0), Semaphore.class); if (s == null) return Kernel.UNKNOWN_SEMID;
            if (s.count + c.arg(1) - s.queue.size() > s.max) return Kernel.SEMA_OVF;
            s.count += c.arg(1); serviceSema(s); return 0;
        });
        k.register(lib, 0x4e3a1105, "sceKernelWaitSema", c -> waitSema(c.arg(0), c.arg(1), c.arg(2), false));
        k.register(lib, 0x6d212bac, "sceKernelWaitSemaCB", c -> waitSema(c.arg(0), c.arg(1), c.arg(2), true));
        k.register(lib, 0x58b1f937, "sceKernelPollSema", c -> {
            Semaphore s = k.object(c.arg(0), Semaphore.class); if (s == null) return Kernel.UNKNOWN_SEMID;
            if (c.arg(1) <= 0) return 0x800201bd; if (s.count < c.arg(1) || s.queue.size() > 0) return Kernel.SEMA_ZERO;
            s.count -= c.arg(1); return 0;
        });
        k.register(lib, 0x8ffdf9a2, "sceKernelCancelSema", c -> {
            Semaphore s = k.object(c.arg(0), Semaphore.class); if (s == null) return Kernel.UNKNOWN_SEMID;
            if (c.arg(2) != 0) m.write32(c.arg(2), s.queue.size());
            wakeAll(s.queue, Kernel.WAIT_CANCEL); s.count = c.arg(1) < 0 ? s.init : c.arg(1); return 0;
        });
        k.register(lib, 0xbc6febc5, "sceKernelReferSemaStatus", c -> {
            Semaphore s = k.object(c.arg(0), Semaphore.class); if (s == null) return Kernel.UNKNOWN_SEMID;
            int p = c.arg(1); m.fill(p + 4, 0, 52); m.writeString(p + 4, s.name, 32); m.write32(p + 36, s.attr); m.write32(p + 40, s.init);
            m.write32(p + 44, s.count); m.write32(p + 48, s.max); m.write32(p + 52, s.queue.size()); return 0;
        });
        k.register(lib, 0x55c20a00, "sceKernelCreateEventFlag", c -> {
            EventFlag e = new EventFlag(); e.name = name(c.arg(0)); e.attr = c.arg(1); e.init = e.pattern = c.arg(2); e.queue = new Queue(e.attr);
            return e.uid = k.newUid(e);
        });
        k.register(lib, 0xef9e4c70, "sceKernelDeleteEventFlag", c -> { EventFlag e = k.object(c.arg(0), EventFlag.class); if (e == null) return Kernel.UNKNOWN_EVFID; wakeAll(e.queue, Kernel.WAIT_DELETE); k.deleteUid(e.uid); return 0; });
        k.register(lib, 0x1fb15a32, "sceKernelSetEventFlag", c -> {
            EventFlag e = k.object(c.arg(0), EventFlag.class); if (e == null) return Kernel.UNKNOWN_EVFID;
            e.pattern |= c.arg(1); serviceFlag(e); return 0;
        });
        k.register(lib, 0x812346e4, "sceKernelClearEventFlag", c -> { EventFlag e = k.object(c.arg(0), EventFlag.class); if (e == null) return Kernel.UNKNOWN_EVFID; e.pattern &= c.arg(1); return 0; });
        k.register(lib, 0x402fcf22, "sceKernelWaitEventFlag", c -> waitFlag(c.arg(0), c.arg(1), c.arg(2), c.arg(3), c.arg(4), false, false));
        k.register(lib, 0x328c546a, "sceKernelWaitEventFlagCB", c -> waitFlag(c.arg(0), c.arg(1), c.arg(2), c.arg(3), c.arg(4), true, false));
        k.register(lib, 0x30fd48f0, "sceKernelPollEventFlag", c -> waitFlag(c.arg(0), c.arg(1), c.arg(2), c.arg(3), 0, false, true));
        k.register(lib, 0xcd203292, "sceKernelCancelEventFlag", c -> {
            EventFlag e = k.object(c.arg(0), EventFlag.class); if (e == null) return Kernel.UNKNOWN_EVFID;
            if (c.arg(2) != 0) m.write32(c.arg(2), e.queue.size()); wakeAll(e.queue, Kernel.WAIT_CANCEL); e.pattern = c.arg(1); return 0;
        });
        k.register(lib, 0xa66b0120, "sceKernelReferEventFlagStatus", c -> {
            EventFlag e = k.object(c.arg(0), EventFlag.class); if (e == null) return Kernel.UNKNOWN_EVFID;
            int p = c.arg(1); m.fill(p + 4, 0, 48); m.writeString(p + 4, e.name, 32); m.write32(p + 36, e.attr); m.write32(p + 40, e.init);
            m.write32(p + 44, e.pattern); m.write32(p + 48, e.queue.size()); return 0;
        });
        // Kernel mutexes
        k.register(lib, 0xb7d098c6, "sceKernelCreateMutex", c -> {
            Mutex x = new Mutex(); x.name = name(c.arg(0)); x.attr = c.arg(1); x.init = c.arg(2); x.queue = new Queue(x.attr);
            if (x.init < 0 || (x.init > 1 && (x.attr & 0x200) == 0)) return 0x800201bd;
            x.uid = k.newUid(x); if (x.init > 0) { x.count = x.init; x.owner = k.current == null ? 0 : k.current.uid; } return x.uid;
        });
        k.register(lib, 0xf8170fbe, "sceKernelDeleteMutex", c -> { Mutex x = k.object(c.arg(0), Mutex.class); if (x == null) return MUTEX_NOT_FOUND; wakeAll(x.queue, Kernel.WAIT_DELETE); k.deleteUid(x.uid); return 0; });
        k.register(lib, 0xb011b11f, "sceKernelLockMutex", c -> lockMutex(c.arg(0), c.arg(1), c.arg(2), false, false));
        k.register(lib, 0x5bf4dd27, "sceKernelLockMutexCB", c -> lockMutex(c.arg(0), c.arg(1), c.arg(2), true, false));
        k.register(lib, 0x0ddcd2c9, "sceKernelTryLockMutex", c -> lockMutex(c.arg(0), c.arg(1), 0, false, true));
        k.register(lib, 0x6b30100f, "sceKernelUnlockMutex", c -> {
            Mutex x = k.object(c.arg(0), Mutex.class); if (x == null) return MUTEX_NOT_FOUND;
            if (c.arg(1) <= 0) return 0x800201bd; if (x.count == 0 || x.owner != k.current.uid) return MUTEX_UNLOCKED;
            if (x.count < c.arg(1)) return 0x800201c7;
            x.count -= c.arg(1); if (x.count == 0) { x.owner = 0; serviceMutex(x); } return 0;
        });
        k.register(lib, 0xa9c2cb9a, "sceKernelReferMutexStatus", c -> {
            Mutex x = k.object(c.arg(0), Mutex.class); if (x == null) return MUTEX_NOT_FOUND;
            int p = c.arg(1); m.fill(p + 4, 0, 52); m.writeString(p + 4, x.name, 32); m.write32(p + 36, x.attr); m.write32(p + 40, x.init);
            m.write32(p + 44, x.count); m.write32(p + 48, x.owner); m.write32(p + 52, x.queue.size()); return 0;
        });
        // Lightweight mutexes: state lives in the guest work area {count, owner, attr, waiters, uid}.
        k.register(lib, 0x19cff145, "sceKernelCreateLwMutex", c -> {
            LwMutex x = new LwMutex(); x.workarea = c.arg(0); x.name = name(c.arg(1)); x.attr = c.arg(2); x.queue = new Queue(x.attr);
            int init = c.arg(3); if (init < 0 || (init > 1 && (x.attr & 0x200) == 0)) return 0x800201bd;
            x.uid = k.newUid(x); m.write32(x.workarea, init); m.write32(x.workarea + 4, init > 0 && k.current != null ? k.current.uid : 0);
            m.write32(x.workarea + 8, x.attr); m.write32(x.workarea + 12, 0); m.write32(x.workarea + 16, x.uid); return 0;
        });
        k.register(lib, 0x60107536, "sceKernelDeleteLwMutex", c -> {
            LwMutex x = lw(c.arg(0)); if (x == null) return LWMUTEX_NOT_FOUND;
            wakeAll(x.queue, Kernel.WAIT_DELETE); k.deleteUid(x.uid); m.write32(x.workarea + 16, 0); return 0;
        });
        k.register(lib, 0x4c145944, "sceKernelReferLwMutexStatusByID", c -> { LwMutex x = k.object(c.arg(0), LwMutex.class); return x == null ? LWMUTEX_NOT_FOUND : referLw(x, c.arg(1)); });
    }
    private void wakeAll(Queue q, int result) { for (GuestThread t : q.ordered()) k.wake(t, result); q.entries.clear(); }
    private int waitSema(int uid, int need, int timeout, boolean cb) {
        Semaphore s = k.object(uid, Semaphore.class); if (s == null) return Kernel.UNKNOWN_SEMID;
        if (need <= 0 || need > s.max) return 0x800201bd;
        if (s.count >= need && s.queue.size() == 0) { s.count -= need; return 0; }
        wait("Semaphore", uid, s.queue, need, timeout, cb); return 0;
    }
    private void serviceSema(Semaphore s) {
        for (GuestThread t : s.queue.ordered()) {
            int need = (Integer)t.wait.data; if (s.count < need) { if (s.queue.priority) continue; break; }
            s.count -= need; k.wake(t, 0);
        }
        s.queue.prune();
    }
    private boolean matches(int pattern, int bits, int mode) { return (mode & 1) == 0 ? (pattern & bits) == bits : (pattern & bits) != 0; }
    private int waitFlag(int uid, int bits, int mode, int out, int timeout, boolean cb, boolean poll) {
        EventFlag e = k.object(uid, EventFlag.class); if (e == null) return Kernel.UNKNOWN_EVFID;
        if (bits == 0) return Kernel.EVF_ILPAT; if ((mode & ~0x31) != 0 || (mode & 0x30) == 0x30) return 0x80020195; // ILLEGAL_MODE
        if (matches(e.pattern, bits, mode)) {
            if (out != 0) m.write32(out, e.pattern);
            if ((mode & 0x20) != 0) e.pattern = 0; else if ((mode & 0x10) != 0) e.pattern &= ~bits; return 0;
        }
        if (poll) { if (out != 0) m.write32(out, e.pattern); return Kernel.EVF_COND; }
        if ((e.attr & 0x200) == 0 && e.queue.size() > 0) return Kernel.EVF_MULTI;
        wait("EventFlag", uid, e.queue, new int[]{bits, mode, out}, timeout, cb); return 0;
    }
    private void serviceFlag(EventFlag e) {
        for (GuestThread t : e.queue.ordered()) {
            int[] w = (int[])t.wait.data;
            if (!matches(e.pattern, w[0], w[1])) continue;
            if (w[2] != 0) m.write32(w[2], e.pattern);
            if ((w[1] & 0x20) != 0) e.pattern = 0; else if ((w[1] & 0x10) != 0) e.pattern &= ~w[0];
            k.wake(t, 0);
        }
        e.queue.prune();
    }
    private int lockMutex(int uid, int count, int timeout, boolean cb, boolean tryOnly) {
        Mutex x = k.object(uid, Mutex.class); if (x == null) return MUTEX_NOT_FOUND;
        if (count <= 0) return 0x800201bd; int self = k.current.uid;
        if (x.count == 0) { x.count = count; x.owner = self; return 0; }
        if (x.owner == self) { if ((x.attr & 0x200) == 0) return 0x800201c8; x.count += count; return 0; }
        if (tryOnly) return MUTEX_LOCKED;
        wait("Mutex", uid, x.queue, count, timeout, cb); return 0;
    }
    private void serviceMutex(Mutex x) {
        List<GuestThread> waiting = x.queue.ordered(); if (waiting.isEmpty()) return;
        GuestThread t = waiting.get(0); x.owner = t.uid; x.count = (Integer)t.wait.data; k.wake(t, 0); x.queue.prune();
    }
    private LwMutex lw(int workarea) { return m.valid(workarea, 20) ? k.object(m.read32(workarea + 16), LwMutex.class) : null; }
    private int referLw(LwMutex x, int p) {
        m.fill(p + 4, 0, 52); m.writeString(p + 4, x.name, 32); m.write32(p + 36, x.attr); m.write32(p + 40, x.uid);
        m.write32(p + 44, x.workarea); m.write32(p + 48, m.read32(x.workarea)); m.write32(p + 52, m.read32(x.workarea + 4)); m.write32(p + 56, x.queue.size()); return 0;
    }
    private int lockLw(int workarea, int count, int timeout, boolean cb, int tryError) {
        LwMutex x = lw(workarea); if (x == null) return LWMUTEX_NOT_FOUND;
        if (count <= 0) return 0x800201bd; int self = k.current.uid, held = m.read32(workarea), owner = m.read32(workarea + 4);
        if (held == 0) { m.write32(workarea, count); m.write32(workarea + 4, self); return 0; }
        if (owner == self) { if ((x.attr & 0x200) == 0) return 0x800201cf; m.write32(workarea, held + count); return 0; }
        if (tryError != 0) return tryError;
        m.write32(workarea + 12, m.read32(workarea + 12) + 1);
        wait("LwMutex", x.uid, x.queue, count, timeout, cb); return 0;
    }
    private int unlockLw(int workarea, int count) {
        LwMutex x = lw(workarea); if (x == null) return LWMUTEX_NOT_FOUND;
        int held = m.read32(workarea), owner = m.read32(workarea + 4);
        if (count <= 0) return 0x800201bd; if (held == 0 || owner != k.current.uid) return LWMUTEX_UNLOCKED;
        if (held < count) return 0x800201ce;
        held -= count; m.write32(workarea, held);
        if (held == 0) {
            m.write32(workarea + 4, 0);
            List<GuestThread> waiting = x.queue.ordered();
            if (!waiting.isEmpty()) {
                GuestThread t = waiting.get(0); m.write32(workarea, (Integer)t.wait.data); m.write32(workarea + 4, t.uid);
                m.write32(workarea + 12, Math.max(0, m.read32(workarea + 12) - 1)); k.wake(t, 0); x.queue.prune();
            }
        }
        return 0;
    }

    // ---- message boxes ----------------------------------------------------------------------------
    /** Messages are guest structures whose first word is the kernel's link pointer and byte 4 the priority (attr 0x400). */
    static final class Mbx { String name; int attr, uid; final java.util.ArrayList<Integer> messages = new java.util.ArrayList<>(); Queue queue; }
    public static final int UNKNOWN_MBXID = 0x8002019b, MBOX_NOMSG = 0x800201b2;
    private void registerMbx() {
        String lib = "ThreadManForUser";
        k.register(lib, 0x8125221d, "sceKernelCreateMbx", c -> { Mbx x = new Mbx(); x.name = name(c.arg(0)); x.attr = c.arg(1); x.queue = new Queue(x.attr); return x.uid = k.newUid(x); });
        k.register(lib, 0x86255ada, "sceKernelDeleteMbx", c -> { Mbx x = k.object(c.arg(0), Mbx.class); if (x == null) return UNKNOWN_MBXID; wakeAll(x.queue, Kernel.WAIT_DELETE); k.deleteUid(x.uid); return 0; });
        k.register(lib, 0xe9b3061e, "sceKernelSendMbx", c -> {
            Mbx x = k.object(c.arg(0), Mbx.class); if (x == null) return UNKNOWN_MBXID;
            int msg = c.arg(1); java.util.List<GuestThread> waiting = x.queue.ordered();
            if (!waiting.isEmpty()) { GuestThread t = waiting.get(0); m.write32((Integer)t.wait.data, msg); k.wake(t, 0); x.queue.prune(); return 0; }
            if ((x.attr & 0x400) != 0) { // priority order: lower value first, FIFO among equals
                int p = m.read8(msg + 4), at = x.messages.size();
                for (int i = 0; i < x.messages.size(); i++) if (m.read8(x.messages.get(i) + 4) > p) { at = i; break; }
                x.messages.add(at, msg);
            } else x.messages.add(msg);
            relink(x); return 0;
        });
        k.register(lib, 0x18260574, "sceKernelReceiveMbx", c -> receiveMbx(c.arg(0), c.arg(1), c.arg(2), false));
        k.register(lib, 0xf3986382, "sceKernelReceiveMbxCB", c -> receiveMbx(c.arg(0), c.arg(1), c.arg(2), true));
        k.register(lib, 0x0d81716a, "sceKernelPollMbx", c -> {
            Mbx x = k.object(c.arg(0), Mbx.class); if (x == null) return UNKNOWN_MBXID;
            if (x.messages.isEmpty()) return MBOX_NOMSG;
            m.write32(c.arg(1), x.messages.remove(0)); relink(x); return 0;
        });
        k.register(lib, 0x87d4dd36, "sceKernelCancelReceiveMbx", c -> {
            Mbx x = k.object(c.arg(0), Mbx.class); if (x == null) return UNKNOWN_MBXID;
            if (c.arg(1) != 0) m.write32(c.arg(1), x.queue.size()); wakeAll(x.queue, Kernel.WAIT_CANCEL); return 0;
        });
        k.register(lib, 0xa8e8c846, "sceKernelReferMbxStatus", c -> {
            Mbx x = k.object(c.arg(0), Mbx.class); if (x == null) return UNKNOWN_MBXID;
            int p = c.arg(1); m.fill(p + 4, 0, 44); m.writeString(p + 4, x.name, 32); m.write32(p + 36, x.attr);
            m.write32(p + 40, x.queue.size()); m.write32(p + 44, x.messages.size()); m.write32(p + 48, x.messages.isEmpty() ? 0 : x.messages.get(0)); return 0;
        });
    }
    private int receiveMbx(int uid, int out, int timeout, boolean cb) {
        Mbx x = k.object(uid, Mbx.class); if (x == null) return UNKNOWN_MBXID;
        if (!x.messages.isEmpty()) { m.write32(out, x.messages.remove(0)); relink(x); return 0; }
        wait("Mbx", uid, x.queue, out, timeout, cb); return 0;
    }
    /** Keeps the guest-visible link words consistent with the queue (circular list, as the PSP kernel keeps it). */
    private void relink(Mbx x) {
        int n = x.messages.size();
        for (int i = 0; i < n; i++) m.write32(x.messages.get(i), x.messages.get((i + 1) % n));
    }

    // ---- virtual timers ---------------------------------------------------------------------------
    static final class VTimer { String name; int uid, handler, argument, gp; long base, accumulated, startedAt = -1, schedule; Object event; }
    public static final int UNKNOWN_VTID = 0x800201bf;
    private long vtimerNow(VTimer v) { return v.accumulated + (v.startedAt >= 0 ? k.now() - v.startedAt : 0); }
    private void registerVTimers() {
        String lib = "ThreadManForUser";
        k.register(lib, 0x20fff560, "sceKernelCreateVTimer", c -> { VTimer v = new VTimer(); v.name = name(c.arg(0)); return v.uid = k.newUid(v); });
        k.register(lib, 0x328f9e52, "sceKernelDeleteVTimer", c -> { VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID; k.cancel(v.event); k.deleteUid(v.uid); return 0; });
        k.register(lib, 0xc68d9437, "sceKernelStartVTimer", c -> {
            VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID;
            if (v.startedAt >= 0) return 1; v.startedAt = k.now(); v.base = k.now(); armVTimer(v); return 0;
        });
        k.register(lib, 0xd0aeee87, "sceKernelStopVTimer", c -> {
            VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID;
            if (v.startedAt < 0) return 0; v.accumulated = vtimerNow(v); v.startedAt = -1; k.cancel(v.event); return 1;
        });
        k.register(lib, 0xb3a59970, "sceKernelGetVTimerBase", c -> { VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID; m.write64(c.arg(1), v.base); return 0; });
        k.register(lib, 0xb7c18b77, "sceKernelGetVTimerBaseWide", c -> { VTimer v = k.object(c.arg(0), VTimer.class); c.returnValue64(v == null ? UNKNOWN_VTID : v.base); return 0; });
        k.register(lib, 0x034a921f, "sceKernelGetVTimerTime", c -> { VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID; m.write64(c.arg(1), vtimerNow(v)); return 0; });
        k.register(lib, 0xc0b3ffd2, "sceKernelGetVTimerTimeWide", c -> { VTimer v = k.object(c.arg(0), VTimer.class); c.returnValue64(v == null ? UNKNOWN_VTID : vtimerNow(v)); return 0; });
        k.register(lib, 0x542ad630, "sceKernelSetVTimerTime", c -> { VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID; long old = vtimerNow(v); setVTimer(v, m.read64(c.arg(1))); m.write64(c.arg(1), old); return 0; });
        k.register(lib, 0xfb6425c3, "sceKernelSetVTimerTimeWide", c -> { VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) { c.returnValue64(UNKNOWN_VTID); return 0; } long old = vtimerNow(v); setVTimer(v, c.arg64(2)); c.returnValue64(old); return 0; });
        k.register(lib, 0xd8b299ae, "sceKernelSetVTimerHandler", c -> setVTimerHandler(c.arg(0), c.arg(1) == 0 ? 0 : m.read64(c.arg(1)), c.arg(2), c.arg(3)));
        k.register(lib, 0x53b00e9a, "sceKernelSetVTimerHandlerWide", c -> setVTimerHandler(c.arg(0), c.arg64(2), c.arg(4), c.arg(5)));
        k.register(lib, 0xd2d615ef, "sceKernelCancelVTimerHandler", c -> { VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID; v.handler = 0; k.cancel(v.event); return 0; });
        k.register(lib, 0x5f32beaa, "sceKernelReferVTimerStatus", c -> {
            VTimer v = k.object(c.arg(0), VTimer.class); if (v == null) return UNKNOWN_VTID;
            int p = c.arg(1); m.fill(p + 4, 0, 60); m.writeString(p + 4, v.name, 32); m.write32(p + 36, v.startedAt >= 0 ? 1 : 0);
            m.write64(p + 40, v.base); m.write64(p + 48, vtimerNow(v)); m.write64(p + 56, v.schedule); m.write32(p + 64, v.handler); m.write32(p + 68, v.argument); return 0;
        });
    }
    private void setVTimer(VTimer v, long time) { v.accumulated = time; if (v.startedAt >= 0) v.startedAt = k.now(); armVTimer(v); }
    private int setVTimerHandler(int uid, long schedule, int handler, int argument) {
        VTimer v = k.object(uid, VTimer.class); if (v == null) return UNKNOWN_VTID;
        v.schedule = schedule; v.handler = handler; v.argument = argument; v.gp = k.cpu.register(28); armVTimer(v); return 0;
    }
    /** Schedules the handler for when the timer reaches its schedule; the handler returns the next delay (0 = stop). */
    private void armVTimer(VTimer v) {
        k.cancel(v.event); v.event = null;
        if (v.handler == 0 || v.startedAt < 0) return;
        long due = k.now() + Math.max(1, v.schedule - vtimerNow(v));
        v.event = k.schedule(due, () -> {
            if (v.handler == 0 || v.startedAt < 0 || k.object(v.uid, VTimer.class) == null) return;
            int area = k.trampolines + 0x40; // scratch for the handler's (schedule, current) arguments
            m.write64(area, v.schedule); m.write64(area + 8, vtimerNow(v));
            k.interrupt(v.handler, v.gp, new int[]{v.uid, area, area + 8, v.argument}, next -> {
                if (next <= 0) { v.handler = 0; return; }
                v.schedule += Integer.toUnsignedLong(next); armVTimer(v);
            });
        });
    }

    // ---- fixed and variable pools ---------------------------------------------------------------
    private void registerPools() {
        String lib = "ThreadManForUser";
        k.register(lib, 0xc07bb470, "sceKernelCreateFpl", c -> {
            int partition = c.arg(1), size = c.arg(3), count = c.arg(4), opt = c.arg(5), alignment = 4;
            if (!k.sysmem.validPartition(partition)) return 0x800200d6; // ILLEGAL_PARTITION
            if (size <= 0 || count <= 0) return 0x800201b7; // ILLEGAL_MEMSIZE
            if (opt != 0 && m.read32(opt) >= 8) alignment = Math.max(4, m.read32(opt + 4));
            int stride = (size + alignment - 1) / alignment * alignment;
            Fpl f = new Fpl(); f.name = name(c.arg(0)); f.attr = c.arg(2); f.blockSize = stride; f.count = count; f.used = new boolean[count]; f.queue = new Queue(f.attr);
            long total = (long)stride * count; if (total > k.memory.size) return Kernel.NO_MEMORY;
            f.base = k.sysmem.allocate(partition, "fpl:" + f.name, (f.attr & 0x4000) != 0 ? SysMem.HIGH : SysMem.LOW, (int)total, 0);
            if (f.base == 0) return Kernel.NO_MEMORY; return f.uid = k.newUid(f);
        });
        k.register(lib, 0xed1410e0, "sceKernelDeleteFpl", c -> { Fpl f = k.object(c.arg(0), Fpl.class); if (f == null) return Kernel.UNKNOWN_FPLID; wakeAll(f.queue, Kernel.WAIT_DELETE); k.sysmem.free(f.base); k.deleteUid(f.uid); return 0; });
        k.register(lib, 0xd979e9bf, "sceKernelAllocateFpl", c -> allocateFpl(c.arg(0), c.arg(1), c.arg(2), false, false));
        k.register(lib, 0xe7282cb6, "sceKernelAllocateFplCB", c -> allocateFpl(c.arg(0), c.arg(1), c.arg(2), true, false));
        k.register(lib, 0x623ae665, "sceKernelTryAllocateFpl", c -> allocateFpl(c.arg(0), c.arg(1), 0, false, true));
        k.register(lib, 0xf6414a71, "sceKernelFreeFpl", c -> {
            Fpl f = k.object(c.arg(0), Fpl.class); if (f == null) return Kernel.UNKNOWN_FPLID;
            int i = (c.arg(1) - f.base) / f.blockSize; if (c.arg(1) < f.base || i >= f.count || f.base + i * f.blockSize != c.arg(1) || !f.used[i]) return Kernel.ILLEGAL_MEMBLOCK;
            f.used[i] = false;
            List<GuestThread> waiting = f.queue.ordered();
            if (!waiting.isEmpty()) { GuestThread t = waiting.get(0); f.used[i] = true; m.write32((Integer)t.wait.data, c.arg(1)); k.wake(t, 0); f.queue.prune(); }
            return 0;
        });
        k.register(lib, 0xa8aa591f, "sceKernelCancelFpl", c -> { Fpl f = k.object(c.arg(0), Fpl.class); if (f == null) return Kernel.UNKNOWN_FPLID; if (c.arg(1) != 0) m.write32(c.arg(1), f.queue.size()); wakeAll(f.queue, Kernel.WAIT_CANCEL); return 0; });
        k.register(lib, 0xd8199e4c, "sceKernelReferFplStatus", c -> {
            Fpl f = k.object(c.arg(0), Fpl.class); if (f == null) return Kernel.UNKNOWN_FPLID;
            int free = 0; for (boolean u : f.used) if (!u) free++;
            int p = c.arg(1); m.fill(p + 4, 0, 52); m.writeString(p + 4, f.name, 32); m.write32(p + 36, f.attr); m.write32(p + 40, f.blockSize);
            m.write32(p + 44, f.count); m.write32(p + 48, free); m.write32(p + 52, f.queue.size()); return 0;
        });
        k.register(lib, 0x56c039b5, "sceKernelCreateVpl", c -> {
            int partition = c.arg(1), size = c.arg(3);
            if (!k.sysmem.validPartition(partition)) return 0x800200d6; if (size <= 0) return 0x800201b7;
            Vpl v = new Vpl(); v.name = name(c.arg(0)); v.attr = c.arg(2); v.size = (size + 7) & ~7; v.queue = new Queue(v.attr);
            v.base = k.sysmem.allocate(partition, "vpl:" + v.name, (v.attr & 0x4000) != 0 ? SysMem.HIGH : SysMem.LOW, v.size, 0);
            if (v.base == 0) return Kernel.NO_MEMORY; return v.uid = k.newUid(v);
        });
        k.register(lib, 0x89b3d48c, "sceKernelDeleteVpl", c -> { Vpl v = k.object(c.arg(0), Vpl.class); if (v == null) return Kernel.UNKNOWN_VPLID; wakeAll(v.queue, Kernel.WAIT_DELETE); k.sysmem.free(v.base); k.deleteUid(v.uid); return 0; });
        k.register(lib, 0xbed27435, "sceKernelAllocateVpl", c -> allocateVpl(c.arg(0), c.arg(1), c.arg(2), c.arg(3), false, false));
        k.register(lib, 0xec0a693f, "sceKernelAllocateVplCB", c -> allocateVpl(c.arg(0), c.arg(1), c.arg(2), c.arg(3), true, false));
        k.register(lib, 0xaf36d708, "sceKernelTryAllocateVpl", c -> allocateVpl(c.arg(0), c.arg(1), c.arg(2), 0, false, true));
        k.register(lib, 0xb736e9ff, "sceKernelFreeVpl", c -> {
            Vpl v = k.object(c.arg(0), Vpl.class); if (v == null) return Kernel.UNKNOWN_VPLID;
            if (v.allocated.remove(c.arg(1)) == null) return Kernel.ILLEGAL_MEMBLOCK;
            for (GuestThread t : v.queue.ordered()) {
                int[] w = (int[])t.wait.data; int a = vplFit(v, w[0]); if (a == 0) break;
                m.write32(w[1], a); k.wake(t, 0);
            }
            v.queue.prune(); return 0;
        });
        k.register(lib, 0x1d371b8a, "sceKernelCancelVpl", c -> { Vpl v = k.object(c.arg(0), Vpl.class); if (v == null) return Kernel.UNKNOWN_VPLID; if (c.arg(1) != 0) m.write32(c.arg(1), v.queue.size()); wakeAll(v.queue, Kernel.WAIT_CANCEL); return 0; });
        k.register(lib, 0x39810265, "sceKernelReferVplStatus", c -> {
            Vpl v = k.object(c.arg(0), Vpl.class); if (v == null) return Kernel.UNKNOWN_VPLID;
            int used = 0; for (int s : v.allocated.values()) used += s;
            int p = c.arg(1); m.fill(p + 4, 0, 48); m.writeString(p + 4, v.name, 32); m.write32(p + 36, v.attr); m.write32(p + 40, v.size);
            m.write32(p + 44, v.size - used); m.write32(p + 48, v.queue.size()); return 0;
        });
    }
    private int allocateFpl(int uid, int out, int timeout, boolean cb, boolean tryOnly) {
        Fpl f = k.object(uid, Fpl.class); if (f == null) return Kernel.UNKNOWN_FPLID;
        for (int i = 0; i < f.count; i++) if (!f.used[i]) { f.used[i] = true; m.write32(out, f.base + i * f.blockSize); return 0; }
        if (tryOnly) return Kernel.NO_MEMORY;
        wait("Fpl", uid, f.queue, out, timeout, cb); return 0;
    }
    /** First-fit allocation inside a VPL (8-byte granularity, 8-byte header like the PSP). Returns 0 when full. */
    private int vplFit(Vpl v, int size) {
        int need = ((size + 7) & ~7) + 8, cursor = v.base;
        for (Map.Entry<Integer, Integer> e : v.allocated.entrySet()) {
            int start = e.getKey() - 8; if (start - cursor >= need) break; cursor = e.getKey() + e.getValue() - 8;
        }
        if (v.base + v.size - cursor < need) return 0;
        int address = cursor + 8; v.allocated.put(address, need); return address;
    }
    private int allocateVpl(int uid, int size, int out, int timeout, boolean cb, boolean tryOnly) {
        Vpl v = k.object(uid, Vpl.class); if (v == null) return Kernel.UNKNOWN_VPLID;
        if (size <= 0 || size > v.size) return 0x800201b7;
        int a = vplFit(v, size); if (a != 0) { m.write32(out, a); return 0; }
        if (tryOnly) return Kernel.NO_MEMORY;
        wait("Vpl", uid, v.queue, new int[]{size, out}, timeout, cb); return 0;
    }

    // ---- time -----------------------------------------------------------------------------------
    private void registerTime() {
        String lib = "ThreadManForUser";
        k.register(lib, 0xceadeb47, "sceKernelDelayThread", c -> delay(Integer.toUnsignedLong(c.arg(0)), false));
        k.register(lib, 0x68da9e36, "sceKernelDelayThreadCB", c -> delay(Integer.toUnsignedLong(c.arg(0)), true));
        k.register(lib, 0xbd123d9e, "sceKernelDelaySysClockThread", c -> delay(m.read64(c.arg(0)), false));
        k.register(lib, 0x1181e963, "sceKernelDelaySysClockThreadCB", c -> delay(m.read64(c.arg(0)), true));
        k.register(lib, 0x369ed59d, "sceKernelGetSystemTimeLow", c -> (int)k.now());
        k.register(lib, 0xdb738f35, "sceKernelGetSystemTime", c -> { m.write64(c.arg(0), k.now()); return 0; });
        k.register(lib, 0x82bc5777, "sceKernelGetSystemTimeWide", c -> { c.returnValue64(k.now()); return 0; });
        k.register(lib, 0x110dec9a, "sceKernelUSec2SysClock", c -> { m.write64(c.arg(1), Integer.toUnsignedLong(c.arg(0))); return 0; });
        k.register(lib, 0xc8cd158c, "sceKernelUSec2SysClockWide", c -> { c.returnValue64(Integer.toUnsignedLong(c.arg(0))); return 0; });
        k.register(lib, 0xba6b92e2, "sceKernelSysClock2USec", c -> {
            long v = m.read64(c.arg(0)); if (c.arg(1) != 0) m.write32(c.arg(1), (int)(v / 1000000)); if (c.arg(2) != 0) m.write32(c.arg(2), (int)(v % 1000000)); return 0;
        });
        k.register(lib, 0xe1619d7c, "sceKernelSysClock2USecWide", c -> {
            long v = c.arg64(0); if (c.arg(2) != 0) m.write32(c.arg(2), (int)(v / 1000000)); if (c.arg(3) != 0) m.write32(c.arg(3), (int)(v % 1000000)); return 0;
        });
        k.register(lib, 0x6652b8ca, "sceKernelSetAlarm", c -> setAlarm(Integer.toUnsignedLong(c.arg(0)), c.arg(1), c.arg(2)));
        k.register(lib, 0xb2c25152, "sceKernelSetSysClockAlarm", c -> setAlarm(m.read64(c.arg(0)), c.arg(1), c.arg(2)));
        k.register(lib, 0x7e65b999, "sceKernelCancelAlarm", c -> { Alarm a = k.object(c.arg(0), Alarm.class); if (a == null) return UNKNOWN_ALMID; k.cancel(a.event); k.deleteUid(a.uid); return 0; });
    }
    private int delay(long micros, boolean cb) {
        GuestThread t = k.current; Wait w = new Wait("Delay", 0); k.waitCurrent(w, 0, cb);
        k.schedule(k.now() + Math.max(micros, 1), () -> { if (t.wait == w) k.wake(t, 0); });
        return 0;
    }
    private int setAlarm(long micros, int handler, int argument) {
        if (handler == 0) return 0x80020192;
        Alarm a = new Alarm(); a.handler = handler; a.argument = argument; a.gp = k.cpu.register(28); a.uid = k.newUid(a);
        armAlarm(a, micros); return a.uid;
    }
    private void armAlarm(Alarm a, long micros) {
        a.event = k.schedule(k.now() + Math.max(micros, 1), () -> {
            if (k.object(a.uid, Alarm.class) == null) return;
            k.interrupt(a.handler, a.gp, new int[]{a.argument}, result -> { if (result == 0) k.deleteUid(a.uid); else armAlarm(a, Integer.toUnsignedLong(result)); });
        });
    }

    // ---- Kernel_Library (user-mode kernel helpers) ------------------------------------------------
    private void registerKernelLibrary() {
        String lib = "Kernel_Library";
        k.register(lib, 0x092968f4, "sceKernelCpuSuspendIntr", c -> { int was = interruptsEnabled ? 1 : 0; interruptsEnabled = false; return was; });
        k.register(lib, 0x5f10d406, "sceKernelCpuResumeIntr", c -> { interruptsEnabled = c.arg(0) != 0; return 0; });
        k.register(lib, 0x3b84732d, "sceKernelCpuResumeIntrWithSync", c -> { interruptsEnabled = c.arg(0) != 0; return 0; });
        k.register(lib, 0x47a0b729, "sceKernelIsCpuIntrSuspended", c -> c.arg(0) == 0 ? 1 : 0);
        k.register(lib, 0xb55249d2, "sceKernelIsCpuIntrEnable", c -> interruptsEnabled ? 1 : 0);
        k.register(lib, 0xa089eca4, "sceKernelMemset", c -> { m.fill(c.arg(0), c.arg(1), c.arg(2)); return c.arg(0); });
        k.register(lib, 0x1839852a, "sceKernelMemcpy", c -> { if (c.arg(2) > 0) m.copy(c.arg(0), c.arg(1), c.arg(2)); return c.arg(0); });
        k.register(lib, 0xbea46419, "sceKernelLockLwMutex", c -> lockLw(c.arg(0), c.arg(1), c.arg(2), false, 0));
        k.register(lib, 0x1fc64e09, "sceKernelLockLwMutexCB", c -> lockLw(c.arg(0), c.arg(1), c.arg(2), true, 0));
        k.register(lib, 0xdc692ee3, "sceKernelTryLockLwMutex", c -> lockLw(c.arg(0), c.arg(1), 0, false, LWMUTEX_LOCKED) == 0 ? 0 : 0x800201c4);
        k.register(lib, 0x37431849, "sceKernelTryLockLwMutex_600", c -> lockLw(c.arg(0), c.arg(1), 0, false, LWMUTEX_LOCKED));
        k.register(lib, 0x15b6446b, "sceKernelUnlockLwMutex", c -> unlockLw(c.arg(0), c.arg(1)));
        k.register(lib, 0xc1734599, "sceKernelReferLwMutexStatus", c -> { LwMutex x = lw(c.arg(0)); return x == null ? LWMUTEX_NOT_FOUND : referLw(x, c.arg(1)); });
        k.register(lib, 0x293b45b8, "sceKernelGetThreadId", c -> k.current == null ? Kernel.ERROR : k.current.uid);
        k.register(lib, 0xd13bde95, "sceKernelCheckThreadStack", c -> k.current == null ? 0 : c.cpu.register(29) - k.current.stack);
    }
    public boolean interruptsEnabled() { return interruptsEnabled; }

    // ---- InterruptManager ----------------------------------------------------------------------
    private void registerInterrupts() {
        String lib = "InterruptManager";
        k.register(lib, 0xca04a2b9, "sceKernelRegisterSubIntrHandler", c -> {
            for (SubInterrupt s : subInterrupts) if (s.interrupt == c.arg(0) && s.sub == c.arg(1)) return 0x80020067; // HANDLER_ALREADY_EXISTS
            SubInterrupt s = new SubInterrupt(); s.interrupt = c.arg(0); s.sub = c.arg(1); s.handler = c.arg(2); s.argument = c.arg(3); s.gp = c.cpu.register(28);
            subInterrupts.add(s); return 0;
        });
        k.register(lib, 0xd61e6961, "sceKernelReleaseSubIntrHandler", c -> subInterrupts.removeIf(s -> s.interrupt == c.arg(0) && s.sub == c.arg(1)) ? 0 : 0x80020068);
        k.register(lib, 0xfb8e22ec, "sceKernelEnableSubIntr", c -> { for (SubInterrupt s : subInterrupts) if (s.interrupt == c.arg(0) && s.sub == c.arg(1)) { s.enabled = true; return 0; } return 0x80020068; });
        k.register(lib, 0x8a389411, "sceKernelDisableSubIntr", c -> { for (SubInterrupt s : subInterrupts) if (s.interrupt == c.arg(0) && s.sub == c.arg(1)) s.enabled = false; return 0; });
        k.register(lib, 0xeee43f47, "sceKernelRegisterUserSpaceIntrStack", c -> 0);
    }
    /** Raises a hardware interrupt, running every enabled sub-handler in interrupt context. */
    public void raise(int interrupt) {
        for (SubInterrupt s : new ArrayList<>(subInterrupts)) if (s.interrupt == interrupt && s.enabled && s.handler != 0)
            k.interrupt(s.handler, s.gp, new int[]{s.sub, s.argument}, r -> { });
    }
}
