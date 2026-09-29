package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Streaming PSAR v3 archive reader. All crypto envelopes are authenticated before decompression. */
public final class PsarArchive {
    public interface Consumer { void file(String name, byte[] bytes) throws IOException; }
    private static final class Block {
        byte[] bytes; int length;
    }
    private static Block block(RandomAccessFile source,long at) throws IOException {
        if(at<16 || at>source.length()-0x150) throw new IOException("PSAR envelope outside archive at "+at);
        source.seek(at); byte[] header=new byte[0x150]; source.readFully(header);
        int size=FirmwareCrypto.psar3EnvelopeSize(header);
        if(at+size>source.length()) throw new IOException("Truncated PSAR payload");
        byte[] envelope=new byte[size]; System.arraycopy(header,0,envelope,0,header.length);
        source.readFully(envelope,header.length,size-header.length);
        Block result=new Block(); result.bytes=FirmwareCrypto.demanglePsar3(envelope); result.length=size;
        return result;
    }
    private static String string(byte[] bytes,int at,int end) throws IOException {
        int last=at; while(last<end && bytes[last]!=0) last++;
        if(last==end) throw new IOException("Unterminated PSAR name");
        return new String(bytes,at,last-at,StandardCharsets.US_ASCII);
    }
    public static int unpack(File archive,Consumer consumer) throws IOException {
        try(RandomAccessFile source=new RandomAccessFile(archive,"r")) {
            if(source.length()<0x300 || source.length()>128L*1024*1024 || source.readInt()!=0x50534152 || source.readUnsignedByte()!=3)
                throw new IOException("Only bounded PSAR version 3 is supported");
            Block info=block(source,16);
            if(info.bytes.length!=0x110) throw new IOException("Invalid PSAR version record");
            String version=string(info.bytes,16,128);
            if(!version.endsWith(",6.61")) throw new IOException("Unsupported PSAR firmware: "+version);
            long cursor=16+info.length;
            Block secondary=block(source,cursor); cursor+=secondary.length;
            Set<String> seen=new HashSet<>(); long expandedTotal=0; int count=0;
            // The official v3 archive ends with a 16-byte opaque trailer, covered by the PBP's pinned digest.
            while(cursor<source.length()-16) {
                if(Thread.currentThread().isInterrupted()) throw new IOException("PSAR extraction cancelled");
                if(++count>4096) throw new IOException("Too many PSAR entries");
                Block descriptor=block(source,cursor); cursor+=descriptor.length;
                if(descriptor.bytes.length!=0x110 || FirmwareCrypto.word(descriptor.bytes,0x100)!=0)
                    throw new IOException("Invalid PSAR entry descriptor");
                String name=string(descriptor.bytes,4,0x100);
                if(name.isEmpty() || !seen.add(name)) throw new IOException("Empty or duplicate PSAR name");
                int packed=FirmwareCrypto.word(descriptor.bytes,0x104),expanded=FirmwareCrypto.word(descriptor.bytes,0x108);
                if(packed<0 || expanded<0 || expanded>8*1024*1024 || (expandedTotal+=expanded)>256L*1024*1024)
                    throw new IOException("PSAR output exceeds bounds");
                byte[] result;
                if(expanded==0) { if(packed!=0) throw new IOException("Nonempty PSAR directory payload"); result=new byte[0]; }
                else {
                    Block payload=block(source,cursor);
                    if(packed!=payload.length) throw new IOException("PSAR packed-length mismatch for "+name+": "+packed+" != "+payload.length);
                    result=inflate(payload.bytes,expanded);
                }
                cursor+=packed; consumer.file(name,result);
            }
            if(cursor!=source.length()-16) throw new IOException("Unexpected PSAR trailer size");
            return count;
        }
    }
    private static byte[] inflate(byte[] compressed,int size) throws IOException {
        Inflater inflater=new Inflater(); inflater.setInput(compressed); byte[] result=new byte[size]; int count=0;
        try {
            while(!inflater.finished() && count<size) {
                int n=inflater.inflate(result,count,size-count); count+=n;
                if(n==0 && !inflater.finished()) throw new IOException("Incomplete PSAR deflate stream");
            }
            if(count!=size || !inflater.finished() || inflater.getRemaining()!=0) throw new IOException("PSAR expanded length or trailing data mismatch");
            return result;
        } catch(DataFormatException e) { throw new IOException("Invalid PSAR deflate data",e); }
        finally { inflater.end(); }
    }
}
