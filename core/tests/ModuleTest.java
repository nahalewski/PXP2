import org.pxp2.core.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.io.*;
import java.util.zip.*;

public final class ModuleTest {
    public static void main(String[] args) throws Exception {
        Path raw=Paths.get(args[0]),out=Paths.get(args[1]);Files.createDirectories(out);
        int files=0,modules=0;Map<String,Integer> failures=new TreeMap<>(),formats=new TreeMap<>();
        for(String line:Files.readAllLines(raw.resolve("table-01g.txt"),StandardCharsets.US_ASCII)) {
            String[] parts=line.split(",",2);if(parts.length!=2 || !parts[1].startsWith("flash0:/"))continue;
            byte[] bytes=Files.readAllBytes(raw.resolve(parts[0]));
            if(bytes.length==0)continue;
            String relative=parts[1].substring(8);Path target=out.resolve(relative).normalize();
            if(!target.startsWith(out))throw new IOException("Bad path");
            try {
                if(bytes.length>=4 && FirmwareCrypto.word(bytes,0)==0x5053507e) {
                    int expected=FirmwareCrypto.word(bytes,0x28);
                    bytes=FirmwareCrypto.decryptModule(bytes); modules++;
                    if((bytes[0]&255)==0x1f && (bytes[1]&255)==0x8b) {
                        try(GZIPInputStream input=new GZIPInputStream(new ByteArrayInputStream(bytes))) {bytes=input.readAllBytes();}
                    }
                    if(FirmwareCrypto.word(bytes,0)==0x45344c4b || FirmwareCrypto.word(bytes,0)==0x45334c4b)bytes=KlDecoder.decode(bytes,expected);
                    String format=Integer.toHexString(FirmwareCrypto.word(bytes,0)); formats.merge(format,1,Integer::sum);
                }
                Files.createDirectories(target.getParent());Files.write(target,bytes);files++;
            } catch(IOException error) {failures.merge(error.getMessage(),1,Integer::sum);System.out.println(relative+": "+error.getMessage());}
        }
        System.out.println("Saved "+files+" flash0 files; decrypted "+modules+" modules; formats="+formats+" failures="+failures);
    }
}
