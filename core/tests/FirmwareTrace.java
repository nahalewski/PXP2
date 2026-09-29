import org.pxp2.core.*;
import java.nio.file.*;
import java.util.*;

public final class FirmwareTrace {
    public static void main(String[] args)throws Exception {
        Memory memory=new Memory();PrxLoader.Module module=PrxLoader.load(Files.readAllBytes(Paths.get(args[0])),memory,0x08800000);
        System.out.printf("Loaded %s: entry=%08x GP=%08x size=%d relocations=%d imports=%d exports=%d%n",module.name,module.entry,module.gp,module.size,module.relocations,module.imports.size(),module.exports.size());
        Map<Integer,String> imports=new HashMap<>();for(PrxLoader.Symbol s:module.imports)imports.put(s.address,s.key());
        Cpu cpu=new Cpu(memory,module.entry);cpu.setRegister(29,0x09fff000);cpu.setRegister(28,module.gp);
        cpu.setRegister(4,0);cpu.setRegister(5,0);
        int count=0;
        try {
            for(;count<1000000;count++) {
                if(imports.containsKey(cpu.pc())) {System.out.println("Unresolved firmware import: "+imports.get(cpu.pc()));break;}
                if(count<32)System.out.printf("%08x %08x%n",cpu.pc(),memory.read32(cpu.pc()));
                cpu.step();
            }
        } catch(IllegalArgumentException|IllegalStateException e) {System.out.println("Stop: "+e.getMessage());}
        System.out.printf("Sony instructions executed=%d PC=%08x RA=%08x%n",count,cpu.pc(),cpu.register(31));
    }
}
