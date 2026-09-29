package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Installs a verified PSP-1000 virtual flash image in app-private storage, never device flash. */
public final class FirmwareInstaller {
    public interface Progress { void update(String message); }
    public static final class Result {
        public final File directory;public final int files,modules;
        Result(File directory,int files,int modules){this.directory=directory;this.files=files;this.modules=modules;}
        public String toString(){return "Sony 6.61 virtual flash prepared (PSP-1000).\n"+files+" files; "+modules+" ELF modules.\nFirmware files are decrypted. XMB has not booted.";}
    }
    private static String hash(byte[] bytes)throws IOException {
        try {StringBuilder s=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static byte[] expand(byte[] data,int expected)throws IOException {
        if(expected<1 || expected>8*1024*1024)throw new IOException("Invalid firmware expanded length");
        int magic=FirmwareCrypto.word(data,0);
        if(magic==0x45344c4b || magic==0x45334c4b)return KlDecoder.decode(data,expected);
        if((data[0]&255)==31 && (data[1]&255)==139) {
            try(GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(data));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[16384];int n;
                while((n=in.read(buffer))!=-1){if(out.size()+n>expected)throw new IOException("Firmware gzip exceeds declared size");out.write(buffer,0,n);}
                if(out.size()!=expected)throw new IOException("Firmware gzip length mismatch");return out.toByteArray();
            }
        }
        if(data.length!=expected)throw new IOException("Unsupported firmware compression/length");return data;
    }
    /** Decrypts and expands an encrypted (~PSP) module; plain ELF input is returned unchanged. */
    public static byte[] decryptPrx(byte[] data)throws IOException {
        if(data.length>=336 && FirmwareCrypto.word(data,0)==0x5053507e)return expand(FirmwareCrypto.decryptModule(data),FirmwareCrypto.word(data,0x28));
        return data;
    }
    public static Result install(File pbp,File storage,Progress progress)throws IOException {
        progress.update("Verifying Sony update...");
        PbpPackage.Extraction packageFiles=PbpPackage.extractSony661(pbp,storage);
        Path base=storage.toPath(),destination=base.resolve("flash-661-01g-v1");
        if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS))return verify(destination);
        Path stage=Files.createTempDirectory(base,".flash-");
        Map<String,String> names=new HashMap<>();Set<String> completed=new HashSet<>();int[] counters={0,0};
        StringBuilder manifest=new StringBuilder("PXP2_FLASH_661_01G_V1\nsource="+PbpPackage.SONY_661_SHA256+"\n");
        try {
            int records=PsarArchive.unpack(new File(packageFiles.directory,"DATA.PSAR"),(name,content)->{
                if(name.equals("00001")) {
                    String table=new String(FirmwareCrypto.decryptTable661(content),StandardCharsets.US_ASCII);
                    for(String line:table.split("\r?\n")) {
                        String[] pair=line.split(",",2);if(pair.length!=2)throw new IOException("Invalid model file table");
                        if(pair[1].startsWith("flash0:/")) {
                            String path=pair[1].substring(8);
                            if(!pair[0].matches("[0-9]{5}") || !path.matches("[A-Za-z0-9_./-]+") || path.startsWith("/") || Arrays.asList(path.split("/")).contains(".."))throw new IOException("Unsafe firmware table path");
                            if(names.put(pair[0],path)!=null)throw new IOException("Duplicate firmware file ID");
                        }
                    }
                    if(names.isEmpty())throw new IOException("Empty model file table");
                }
                String relative=names.get(name);if(relative==null)return;
                if(!completed.add(relative))throw new IOException("Duplicate firmware destination");
                Path file=stage.resolve("flash0").resolve(relative).normalize();
                if(!file.startsWith(stage.resolve("flash0")))throw new IOException("Firmware path escape");
                if(content.length==0){Files.createDirectories(file);return;}
                byte[] output=content;
                if(content.length>=336 && FirmwareCrypto.word(content,0)==0x5053507e)output=expand(FirmwareCrypto.decryptModule(content),FirmwareCrypto.word(content,0x28));
                if(relative.endsWith(".prx")) {
                    if(output.length<52 || FirmwareCrypto.word(output,0)!=0x464c457f)throw new IOException("Non-ELF PRX: "+relative);
                    counters[1]++;
                }
                Files.createDirectories(file.getParent());
                try(FileOutputStream out=new FileOutputStream(file.toFile())){out.write(output);out.getFD().sync();}
                counters[0]++;manifest.append(hash(output)).append(' ').append(output.length).append(' ').append(relative).append('\n');
                if(counters[0]%20==0)progress.update("Decrypting and installing virtual flash: "+counters[0]+" files...");
            });
            if(records!=436 || counters[0]!=312 || counters[1]!=215 || completed.size()!=names.size())throw new IOException("Incomplete 6.61 model installation");
            if(!Files.isRegularFile(stage.resolve("flash0/vsh/module/vshmain.prx")))throw new IOException("XMB module missing");
            Files.write(stage.resolve("manifest.txt"),manifest.toString().getBytes(StandardCharsets.UTF_8));
            verify(stage);Files.move(stage,destination,StandardCopyOption.ATOMIC_MOVE);
            return new Result(destination.toFile(),counters[0],counters[1]);
        } finally {
            if(Files.exists(stage,LinkOption.NOFOLLOW_LINKS)) {
                // This tree was created by this operation. Never follow links during cleanup.
                try(java.util.stream.Stream<Path> paths=Files.walk(stage)) {
                    for(Path path:paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new))Files.delete(path);
                }
            }
        }
    }
    public static Result verify(Path directory)throws IOException {
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid firmware directory");
        List<String> lines=Files.readAllLines(directory.resolve("manifest.txt"),StandardCharsets.UTF_8);
        if(lines.size()!=314 || !lines.get(0).equals("PXP2_FLASH_661_01G_V1") || !lines.get(1).equals("source="+PbpPackage.SONY_661_SHA256))throw new IOException("Invalid firmware manifest");
        Set<String> seen=new HashSet<>();int modules=0;
        for(String line:lines.subList(2,lines.size())) {
            String[] parts=line.split(" ",3);if(parts.length!=3 || !parts[0].matches("[a-f0-9]{64}") || !seen.add(parts[2]))throw new IOException("Invalid firmware manifest entry");
            Path file=directory.resolve("flash0").resolve(parts[2]).normalize();
            if(!file.startsWith(directory.resolve("flash0")) || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>8*1024*1024)throw new IOException("Missing firmware file");
            byte[] data=Files.readAllBytes(file);
            if(!Integer.toString(data.length).equals(parts[1]) || !hash(data).equals(parts[0]))throw new IOException("Changed firmware file: "+parts[2]);
            if(parts[2].endsWith(".prx"))modules++;
        }
        if(modules!=215)throw new IOException("Incorrect firmware module count");return new Result(directory.toFile(),312,215);
    }
}
