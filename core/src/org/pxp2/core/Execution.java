package org.pxp2.core;

/** Bounded guest execution with explicit stop reasons. No Android/host syscalls are forwarded. */
public final class Execution {
    public static final class Result {
        public final int instructions, pc, sum, delayCount;
        public final String reason;
        private Result(Cpu cpu, int count, String reason) {
            instructions=count; pc=cpu.pc(); sum=cpu.register(9); delayCount=cpu.register(10); this.reason=reason;
        }
        public String toString() { return String.format(java.util.Locale.ROOT,"Executed %d instructions; PC=%08x\nStop: %s",instructions,pc,reason); }
    }
    public static Result run(ElfLoader.Loaded program, int budget) {
        if (budget < 1 || budget > 1000000) throw new IllegalArgumentException("Invalid instruction budget");
        Cpu cpu = new Cpu(program.memory, program.entry);
        for (int count=0;count<budget;count++) {
            if (Thread.currentThread().isInterrupted()) return new Result(cpu,count,"Cancelled");
            if (!program.executable(cpu.pc())) return new Result(cpu,count,"PC left the executable segments");
            try { cpu.step(); }
            catch (IllegalArgumentException | IllegalStateException e) { return new Result(cpu,count,e.getMessage()); }
        }
        return new Result(cpu,budget,"Instruction budget reached");
    }
}
