import org.pxp2.core.*;
import java.nio.file.*;
public final class PsarTest {
    public static void main(String[] args) throws Exception {
        Path source=Paths.get(args[0]),out=Paths.get(args[1]); Files.createDirectories(out);
        int count=PsarArchive.unpack(source.toFile(),(name,bytes)->{
            if(!name.matches("[A-Za-z0-9_:.-]+")) throw new java.io.IOException("Unexpected raw record name: "+name);
            Files.write(out.resolve(name.replace(':','_')),bytes);
            if(name.equals("00001")) {
                byte[] table=FirmwareCrypto.decryptTable661(bytes);
                Files.write(out.resolve("table-01g.txt"),table);
                System.out.println("Decrypted model 01g table: "+table.length+" bytes");
            }
        });
        System.out.println("Authenticated and unpacked "+count+" PSAR records.");
    }
}
