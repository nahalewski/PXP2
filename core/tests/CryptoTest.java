import org.pxp2.core.*;
import java.nio.file.*;
import java.util.*;

public final class CryptoTest {
    public static void main(String[] args) throws Exception {
        byte[] key=FirmwareCrypto.hex("2b7e151628aed2a6abf7158809cf4f3c");
        if(!Arrays.equals(FirmwareCrypto.cmac(key,new byte[0]),FirmwareCrypto.hex("bb1d6929e95937287fa37d129b756746"))) throw new AssertionError("Empty CMAC");
        if(!Arrays.equals(FirmwareCrypto.cmac(key,FirmwareCrypto.hex("6bc1bee22e409f96e93d7e117393172a")),FirmwareCrypto.hex("070a16b46b4d4144f79bdd9dd04a287c"))) throw new AssertionError("Block CMAC");
        System.out.println("PASS: RFC 4493 CMAC vectors");
        if(args.length>0) {
            Path source=Paths.get(args[0]); byte[] pbp=Files.readAllBytes(source);
            int updater=FirmwareCrypto.word(pbp,32),psar=FirmwareCrypto.word(pbp,36);
            byte[] p=Arrays.copyOfRange(pbp,psar+16,psar+16+0x260);
            byte[] header=FirmwareCrypto.demanglePsar3(p);
            System.out.println("Authenticated PSAR first record: "+new String(header,java.nio.charset.StandardCharsets.ISO_8859_1).replace('\0',' '));
            Files.write(source.resolveSibling("psar-first-record.bin"),header);
            byte[] program=FirmwareCrypto.decryptLegacy(Arrays.copyOfRange(pbp,updater,psar));
            Files.write(source.resolveSibling("updater-decrypted.bin"),program);
            System.out.println("Authenticated updater bytes: "+program.length+" signature: "+Integer.toHexString(FirmwareCrypto.word(program,0)));
        }
    }
}
