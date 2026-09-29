package org.pxp2.core;

import java.util.*;

/**
 * Original PSP memory partition allocator. The layout follows the RAM size chosen for the machine (see psp.md
 * "System memory size"):
 * <ul>
 * <li>All sizes: partition 1 kernel 0x08000000 (4 MiB; 3/4 alias it), 5 VSH/volatile 0x08400000 (4 MiB, the block
 *     sceKernelVolatileMemLock hands out), 2 user 0x08800000 (24 MiB; 6 aliases it). This is the whole PSP-1000.</li>
 * <li>64 MiB (PSP-2000/3000/Go): also partition 8 at 0x0a000000 (28 MiB, kernel "extra" memory) and partition 10
 *     at 0x0bc00000 (4 MiB), the slim layout; games still get the 24 MiB user partition.</li>
 * <li>128 MiB (non-standard): partition 8 grows to 92 MiB and partition 10 moves to 0x0fc00000.</li>
 * <li>High-memory layout ({@link #useHighMemory}, CFW style, for homebrew whose PARAM.SFO has MEMSIZE = 1):
 *     partition 8 is given to the user partition, which becomes 52 MiB (64 MiB RAM) or 116 MiB (128 MiB).</li>
 * </ul>
 */
public final class SysMem {
    public static final int LOW = 0, HIGH = 1, ADDR = 2, LOW_ALIGNED = 3, HIGH_ALIGNED = 4;
    public static final class Block { public int uid; public final int partition, address, size; public final String name; Block(int p, int a, int s, String n) { partition = p; address = a; size = s; name = n; } }
    static final class Partition {
        final int id, base, size; final TreeMap<Integer, Block> blocks = new TreeMap<>();
        Partition(int id, int base, int size) { this.id = id; this.base = base; this.size = size; }
    }
    private final Kernel kernel;
    private final Map<Integer, Partition> partitions = new HashMap<>();
    private final Map<Integer, Block> byAddress = new HashMap<>();

    /** Partitions reported by {@link #partitionOf} and {@link #report}, in address order. */
    private int[] reported;
    /** Main RAM size in bytes. */
    public final int ramSize;
    private boolean highMemory;

    SysMem(Kernel kernel) {
        this.kernel = kernel; ramSize = kernel.memory.size;
        Partition k = new Partition(1, 0x08000000, 0x400000), v = new Partition(5, 0x08400000, 0x400000);
        partitions.put(1, k); partitions.put(3, k); partitions.put(4, k); partitions.put(5, v);
        layoutUser(false);
    }
    /** Lays out user partition 2 (alias 6) and, with more than 32 MiB, partitions 8 and 10. */
    private void layoutUser(boolean high) {
        int extra = ramSize - Memory.SIZE; // memory above 0x0a000000
        Partition u = new Partition(2, 0x08800000, 0x1800000 + (high ? extra - 0x400000 : 0));
        partitions.put(2, u); partitions.put(6, u); partitions.remove(8); partitions.remove(10);
        if (extra > 0) {
            if (!high) partitions.put(8, new Partition(8, 0x0a000000, extra - 0x400000));
            partitions.put(10, new Partition(10, Memory.BASE + ramSize - 0x400000, 0x400000));
            reported = high ? new int[]{1, 5, 2, 10} : new int[]{1, 5, 2, 8, 10};
        } else reported = new int[]{1, 5, 2};
        highMemory = high;
    }
    /**
     * Switches to the CFW-style high-memory layout: the user partition takes partition 8's memory. Only possible with
     * more than 32 MiB of RAM and before anything is allocated in partitions 2 or 8. Returns whether it is in effect.
     */
    public boolean useHighMemory() {
        if (highMemory) return true;
        if (ramSize <= Memory.SIZE || !partitions.get(2).blocks.isEmpty() || !partitions.get(8).blocks.isEmpty()) return false;
        layoutUser(true); return true;
    }
    public boolean highMemory() { return highMemory; }
    public boolean validPartition(int id) { return partitions.containsKey(id); }
    public int partitionBase(int id) { return partitions.get(id).base; }
    public int partitionSize(int id) { return partitions.get(id).size; }

    /** Returns the allocated address, or 0 when the request cannot be satisfied. */
    public int allocate(int partitionId, String name, int type, int size, int address) {
        Partition p = partitions.get(partitionId);
        if (p == null || size <= 0 || size > p.size) return 0;
        int alignment = 0x100;
        if (type == LOW_ALIGNED || type == HIGH_ALIGNED) { if (address == 0 || (address & (address - 1)) != 0) return 0; alignment = Math.max(alignment, address); }
        size = (size + 0xff) & ~0xff;
        int chosen = -1;
        if (type == ADDR) {
            int start = address & ~0xff; if (start < p.base || (long)start + size > (long)p.base + p.size) return 0;
            if (free(p, start, size)) chosen = start;
        } else {
            List<int[]> gaps = new ArrayList<>(); int cursor = p.base;
            for (Block b : p.blocks.values()) { if (b.address > cursor) gaps.add(new int[]{cursor, b.address}); cursor = Math.max(cursor, b.address + b.size); }
            if (cursor < p.base + p.size) gaps.add(new int[]{cursor, p.base + p.size});
            boolean high = type == HIGH || type == HIGH_ALIGNED;
            if (high) Collections.reverse(gaps);
            for (int[] g : gaps) {
                int at = high ? ((g[1] - size) & -alignment) : ((g[0] + alignment - 1) & -alignment);
                if (at >= g[0] && (long)at + size <= g[1]) { chosen = at; break; }
            }
        }
        if (chosen < 0) return 0;
        Block b = new Block(p.id, chosen, size, name == null ? "block" : name);
        b.uid = kernel.newUid(b); p.blocks.put(chosen, b); byAddress.put(chosen, b);
        return chosen;
    }
    private static boolean free(Partition p, int start, int size) {
        Map.Entry<Integer, Block> before = p.blocks.floorEntry(start + size - 1);
        return before == null || before.getValue().address + before.getValue().size <= start;
    }
    public Block block(int address) { return byAddress.get(address); }
    /** The allocated block containing {@code address}, or null. */
    public Block blockContaining(int address) {
        for (Block b : byAddress.values()) if (Integer.compareUnsigned(address - b.address, b.size) < 0) return b;
        return null;
    }
    /** Partition id whose range contains the address (1 kernel, 5 VSH, 2 user, 8/10 extra memory), or 0. */
    public int partitionOf(int address) {
        for (int id : reported) { Partition p = partitions.get(id); if (Integer.compareUnsigned(address - p.base, p.size) < 0) return id; }
        return 0;
    }
    public boolean free(int address) {
        Block b = byAddress.remove(address); if (b == null) return false;
        partitions.get(b.partition).blocks.remove(address); kernel.deleteUid(b.uid); return true;
    }
    public int maxFree(int partitionId) {
        Partition p = partitions.get(partitionId); int best = 0, cursor = p.base;
        for (Block b : p.blocks.values()) { best = Math.max(best, b.address - cursor); cursor = Math.max(cursor, b.address + b.size); }
        return Math.max(best, p.base + p.size - cursor);
    }
    public int totalFree(int partitionId) {
        Partition p = partitions.get(partitionId); int used = 0; for (Block b : p.blocks.values()) used += b.size; return p.size - used;
    }
    /** Largest allocations of a partition, for diagnostics. */
    public String blocks(int partitionId, int limit) {
        List<Block> list = new ArrayList<>(partitions.get(partitionId).blocks.values()); list.sort((x, y) -> Integer.compare(y.size, x.size));
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(limit, list.size()); i++) { Block b = list.get(i); s.append(String.format(Locale.ROOT, "  %08x %7d KiB %s%n", b.address, b.size / 1024, b.name)); }
        return s.toString();
    }
    public String report() {
        StringBuilder s = new StringBuilder();
        s.append(String.format(Locale.ROOT, "  RAM %d MiB%s\n", ramSize >> 20, highMemory ? ", high-memory layout" : ""));
        for (int id : reported) s.append(String.format(Locale.ROOT, "  partition %d at %08x: %d KiB free of %d KiB, %d blocks\n", id, partitions.get(id).base, totalFree(id) / 1024, partitions.get(id).size / 1024, partitions.get(id).blocks.size()));
        return s.toString();
    }
}
