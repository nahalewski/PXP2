import org.pxp2.core.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class PipelineTest {
    private static int checks;
    private interface Action { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void rejects(Action action) throws Exception {
        checks++;
        try { action.run(); } catch(IOException expected) { return; }
        throw new AssertionError("Expected rejection " + checks);
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();
        for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02x",b&255));
        return result.toString();
    }
    private static byte[] pbp() {
        ByteBuffer b=ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0,0x50425000); b.putInt(4,0x10000);
        for(int i=0;i<8;i++) b.putInt(8+i*4,40+i*4);
        for(int i=40;i<80;i++) b.put(i,(byte)i);
        return b.array();
    }
    private static void put(byte[] bytes,int at,int value) { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(at,value); }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory("pxp2-pipeline-test-");
        try {
            Path source=root.resolve("sample.pbp"); byte[] good=pbp(); Files.write(source,good);
            try(RandomAccessFile input=new RandomAccessFile(source.toFile(),"r")) {
                java.util.List<PbpPackage.Section> sections=PbpPackage.inspect(input);
                check(sections.size()==8); check(sections.get(7).size==12);
            }
            File target=root.resolve("output").toFile();
            PbpPackage.Extraction result=PbpPackage.extractVerified(source.toFile(),target,good.length,hash(good));
            check(Arrays.equals(Files.readAllBytes(result.directory.toPath().resolve("DATA.PSAR")),Arrays.copyOfRange(good,68,80)));
            check(Files.readString(result.directory.toPath().resolve("manifest.txt")).contains("firmware_installed=false"));
            check(PbpPackage.extractVerified(source.toFile(),target,good.length,hash(good)).directory.equals(result.directory));
            Files.write(result.directory.toPath().resolve("DATA.PSP"),new byte[4]);
            rejects(()->PbpPackage.extractVerified(source.toFile(),target,good.length,hash(good)));
            byte[] bad=good.clone(); bad[50]^=1; Files.write(source,bad);
            rejects(()->PbpPackage.extractVerified(source.toFile(),root.resolve("bad-hash").toFile(),good.length,hash(good)));
            try(java.util.stream.Stream<Path> children=Files.list(root.resolve("bad-hash"))) { check(children.count()==0); }
            for(int type=0;type<6;type++) {
                byte[] broken=good.clone();
                switch(type) {
                    case 0: broken[0]=1; break;
                    case 1: put(broken,4,2); break;
                    case 2: put(broken,8,39); break;
                    case 3: put(broken,16,41); break;
                    case 4: put(broken,36,81); break;
                    case 5: broken=Arrays.copyOf(broken,39); break;
                }
                Files.write(source,broken);
                rejects(()->{ try(RandomAccessFile input=new RandomAccessFile(source.toFile(),"r")){PbpPackage.inspect(input);} });
            }
            // Empty sections are legitimate; they must not create placeholder files.
            byte[] empty=good.clone(); put(empty,12,40); Files.write(source,empty);
            PbpPackage.Extraction zero=PbpPackage.extractVerified(source.toFile(),root.resolve("empty").toFile(),empty.length,hash(empty));
            check(!new File(zero.directory,"PARAM.SFO").exists());

            System.out.println(ElfDiagnostic.run());
            byte[] elf=ElfDiagnostic.program(); ElfLoader.Loaded loaded=ElfLoader.load(elf);
            check(loaded.memory.read32(loaded.entry+28)==0); check(loaded.memory.read32(loaded.entry+60)==0);
            check(Execution.run(ElfLoader.load(elf),3).reason.equals("Instruction budget reached"));
            byte[] syscall=elf.clone(); put(syscall,0x100,12);
            Execution.Result trap=Execution.run(ElfLoader.load(syscall),100);
            check(trap.instructions==0 && trap.pc==0x08800000 && trap.reason.contains("kernel"));
            byte[] escape=elf.clone(); put(escape,0x100,0x0a200010); put(escape,0x104,0);
            check(Execution.run(ElfLoader.load(escape),100).reason.contains("left"));
            byte[] unknown=elf.clone(); put(unknown,0x100,0xffffffff);
            check(Execution.run(ElfLoader.load(unknown),100).reason.contains("Unsupported"));
            for(int type=0;type<13;type++) {
                byte[] broken=elf.clone();
                switch(type) {
                    case 0: broken[0]=0; break;
                    case 1: broken[4]=2; break;
                    case 2: broken[5]=2; break;
                    case 3: broken[18]=3; break;
                    case 4: broken[16]=3; break;
                    case 5: put(broken,28,0xfffffff0); break;
                    case 6: put(broken,68,65); break;
                    case 7: put(broken,60,0x09fffff0); break;
                    case 8: put(broken,24,0x0880001c); break;
                    case 9: put(broken,76,4); break;
                    case 10: put(broken,52,2); break;
                    case 11: put(broken,80,3); break;
                    case 12: broken=Arrays.copyOf(broken,51); break;
                }
                final byte[] invalid=broken; rejects(()->ElfLoader.load(invalid));
            }
            System.out.println("PASS: " + checks + " package and ELF checks, corruption rejection and execution limits.");
        } finally {
            try(java.util.stream.Stream<Path> paths=Files.walk(root)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(p);
            }
        }
        if(args.length==2) {
            PbpPackage.Extraction actual=PbpPackage.extractSony661(new File(args[0]),new File(args[1]));
            System.out.println(actual.report); System.out.println(FirmwareProbe.inspect(actual));
        }
    }
}
