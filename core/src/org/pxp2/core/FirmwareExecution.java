package org.pxp2.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded execution of real installed Sony modules. Unknown services stop with evidence. */
public final class FirmwareExecution {
    private final Memory memory=new Memory();
    private final Map<Integer,PrxLoader.Symbol> imports=new HashMap<>();
    private final Map<String,PrxLoader.Symbol> exports=new HashMap<>();
    private final Deque<String> trace=new ArrayDeque<>();
    private final List<String> calls=new ArrayList<>();
    private long instructions;
    private int serviceCalls;
    private PrxLoader.Module load(Path file,int base)throws IOException {
        PrxLoader.Module module=PrxLoader.load(Files.readAllBytes(file),memory,base);
        for(PrxLoader.Symbol symbol:module.imports)imports.put(symbol.address,symbol);
        for(PrxLoader.Symbol symbol:module.exports)if(!symbol.library.isEmpty())exports.put(symbol.key(),symbol);
        return module;
    }
    private boolean service(PrxLoader.Symbol symbol,Cpu cpu) {
        if(symbol.library.equals("SysMemUserForUser") && symbol.nid==0xacbd88ca) {
            cpu.setRegister(2,24*1024*1024);cpu.returnToCaller();serviceCalls++;return true;
        }
        if(symbol.library.equals("ThreadManForUser") && symbol.nid==0x369ed59d) {
            // Deterministic virtual microsecond clock; not a fixed success placeholder.
            cpu.setRegister(2,(int)(instructions/100));cpu.returnToCaller();serviceCalls++;return true;
        }
        return false;
    }
    private String run(PrxLoader.Module module,int budget) {
        Cpu cpu=new Cpu(memory,module.entry);cpu.setRegister(29,0x09fff000);cpu.setRegister(28,module.gp);
        cpu.setRegister(31,0x09ffff00);memory.write32(0x09ffff00,0x0000000d);
        try {
            for(int step=0;step<budget;step++) {
                if(Thread.currentThread().isInterrupted())return "Execution cancelled";
                if(cpu.pc()==0x09ffff00)return "Module returned "+cpu.register(2);
                PrxLoader.Symbol symbol=imports.get(cpu.pc());
                if(symbol!=null) {
                    if(calls.size()<64)calls.add(symbol.key());
                    if(service(symbol,cpu))continue;
                    PrxLoader.Symbol target=exports.get(symbol.key());
                    if(target==null)return "Unimplemented import "+symbol.key()+" at "+String.format("%08x",cpu.pc())+String.format(" args=%08x,%08x,%08x,%08x stack=%08x,%08x",cpu.register(4),cpu.register(5),cpu.register(6),cpu.register(7),memory.read32(cpu.register(29)+16),memory.read32(cpu.register(29)+20));
                    memory.write32(symbol.address,0x08000000|((target.address>>>2)&0x03ffffff));memory.write32(symbol.address+4,0);
                }
                trace.addLast(String.format(Locale.ROOT,"%08x %08x",cpu.pc(),memory.read32(cpu.pc())));if(trace.size()>32)trace.removeFirst();
                cpu.step();instructions++;
            }
            return "Instruction budget reached";
        } catch(IllegalArgumentException|IllegalStateException e) {
            return String.format(Locale.ROOT,"%s; PC=%08x RA=%08x",e.getMessage(),cpu.pc(),cpu.register(31));
        }
    }
    public static String diagnose(File installation)throws IOException {
        FirmwareInstaller.verify(installation.toPath());
        FirmwareExecution machine=new FirmwareExecution();Path flash=installation.toPath().resolve("flash0");
        PrxLoader.Module paf=machine.load(flash.resolve("vsh/module/paf.prx"),0x08900000);
        PrxLoader.Module vsh=machine.load(flash.resolve("vsh/module/vshmain.prx"),0x08800000);
        String result=machine.run(paf,1000000);
        String phase="PAF initialization";
        if(result.equals("Module returned 0")) {phase="VSH initialization";result=machine.run(vsh,1000000);}
        String report="Real Sony firmware startup diagnostic\nPhase: "+phase+"\nSony instructions executed: "+machine.instructions+"\nKernel services handled: "+machine.serviceCalls+"\nStop: "+result+"\nUnlinked variable imports: "+(paf.unlinkedVariables+vsh.unlinkedVariables)+"\nXMB has not rendered.\nImports reached: "+machine.calls;
        Files.write(installation.toPath().resolve("startup-trace.txt"),(report+"\nLast instructions:\n"+String.join("\n",machine.trace)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return report;
    }
}
