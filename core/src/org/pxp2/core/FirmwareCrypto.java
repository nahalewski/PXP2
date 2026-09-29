package org.pxp2.core;

import java.io.IOException;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.util.*;

/** PXP2 Java implementation of the legacy PSP firmware envelope using platform cryptography.
 * Protocol constants/format research: PSARDumper (PspPet and contributors),
 * KIRK research by Draan and the PSP community. See CRYPTO-NOTICES.md. */
public final class FirmwareCrypto {
    public static byte[] hex(String text) {
        byte[] result=new byte[text.length()/2];
        for(int i=0;i<result.length;i++) result[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return result;
    }
    public static int word(byte[] data,int at) throws IOException {
        if(at<0 || at>data.length-4) throw new IOException("Truncated firmware field");
        return (data[at]&255)|((data[at+1]&255)<<8)|((data[at+2]&255)<<16)|(data[at+3]<<24);
    }
    private static byte[] slice(byte[] data,int offset,int length) throws IOException {
        if(offset<0 || length<0 || offset>data.length-length) throw new IOException("Truncated crypto envelope");
        return Arrays.copyOfRange(data,offset,offset+length);
    }
    public static byte[] aes(byte[] key,byte[] input) throws IOException {
        try {
            Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(new byte[16]));
            return cipher.doFinal(input);
        } catch(GeneralSecurityException e) { throw new IOException("AES decoding failed",e); }
    }
    private static byte[] doubled(byte[] input) {
        byte[] result=new byte[16]; int carry=0;
        for(int i=15;i>=0;i--) { int value=input[i]&255; result[i]=(byte)((value<<1)|carry); carry=value>>>7; }
        if(carry!=0) result[15]^=(byte)0x87;
        return result;
    }
    public static byte[] cmac(byte[] key,byte[] data) throws IOException {
        try {
            Cipher aes=Cipher.getInstance("AES/ECB/NoPadding"); aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"));
            byte[] first=doubled(aes.doFinal(new byte[16]));
            boolean complete=data.length>0 && (data.length&15)==0;
            byte[] subkey=complete?first:doubled(first);
            int blocks=Math.max(1,(data.length+15)/16); byte[] chain=new byte[16];
            for(int block=0;block<blocks;block++) {
                for(int i=0;i<16;i++) {
                    int index=block*16+i;
                    byte value=index<data.length?data[index]:index==data.length?(byte)0x80:0;
                    chain[i]^=value;
                    if(block==blocks-1) chain[i]^=subkey[i];
                }
                chain=aes.doFinal(chain);
            }
            return chain;
        } catch(GeneralSecurityException e) { throw new IOException("CMAC failed",e); }
    }
    public static byte[] decryptLegacy(byte[] prx) throws IOException {
        if(prx.length<0x150) throw new IOException("Truncated PSP module");
        int tag=word(prx,0xd0);
        byte[] mask,seed;
        if(tag==0x0e000000) {mask=FirmwareKeys.PSAR_MASK;seed=FirmwareKeys.SEED_51;}
        else if(tag==0x0b000000) {mask=FirmwareKeys.UPDATER_MASK;seed=FirmwareKeys.SEED_4E;}
        else throw new IOException(String.format("Unsupported legacy PRX tag %08x",tag));
        // The legacy PRX envelope wraps the first 64 bytes of a KIRK private header.
        byte[] coded=new byte[112];
        System.arraycopy(prx,0x110,coded,0,64);
        for(int i=0;i<coded.length;i++) coded[i]^=mask[20+i];
        byte[] decoded=aes(seed,coded);
        byte[] packet=new byte[prx.length-64];
        for(int i=0;i<64;i++) packet[i]=(byte)(decoded[i]^mask[32+i]);
        packet[0x60]=1;
        System.arraycopy(prx,0xb0,packet,0x70,32);
        System.arraycopy(prx,0,packet,0x90,128);
        System.arraycopy(prx,0x150,packet,0x110,prx.length-0x150);
        return privatePacket(packet);
    }
    public static byte[] decryptModule(byte[] prx) throws IOException {
        if(prx.length<0x150) throw new IOException("Truncated PSP module");
        int tag=word(prx,0xd0); byte[] key,seed;
        switch(tag) {
            case 0x0e000000: case 0x0b000000: return decryptLegacy(prx);
            case 0xd82310f0: key=FirmwareKeys.keys02G_E;seed=FirmwareKeys.SEED_51;break;
            case 0xd8231ef0: key=FirmwareKeys.keys03G_E;seed=FirmwareKeys.SEED_51;break;
            case 0x4c9494f0:key=FirmwareKeys.keys660_k1;seed=FirmwareKeys.SEED_43;break;
            case 0x4c9495f0:key=FirmwareKeys.keys660_k2;seed=FirmwareKeys.SEED_43;break;
            case 0x4c9490f0:key=FirmwareKeys.keys660_k3;seed=FirmwareKeys.SEED_43;break;
            case 0x4c9491f0:key=FirmwareKeys.keys660_k8;seed=FirmwareKeys.SEED_43;break;
            case 0x4c9493f0:key=FirmwareKeys.keys660_k4;seed=FirmwareKeys.SEED_43;break;
            case 0x4c9497f0:key=FirmwareKeys.keys660_k5;seed=FirmwareKeys.SEED_43;break;
            case 0x4c9492f0:key=FirmwareKeys.keys660_k6;seed=FirmwareKeys.SEED_43;break;
            case 0x4c9496f0:key=FirmwareKeys.keys660_k7;seed=FirmwareKeys.SEED_43;break;
            case 0x457b90f0:key=FirmwareKeys.keys660_v1;seed=FirmwareKeys.SEED_5B;break;
            case 0x457b91f0:key=FirmwareKeys.keys660_v7;seed=FirmwareKeys.SEED_5B;break;
            case 0x457b92f0:key=FirmwareKeys.keys660_v6;seed=FirmwareKeys.SEED_5B;break;
            case 0x457b93f0:key=FirmwareKeys.keys660_v3;seed=FirmwareKeys.SEED_5B;break;
            case 0x380290f0:key=FirmwareKeys.keys660_v2;seed=FirmwareKeys.SEED_5A;break;
            case 0x380291f0:key=FirmwareKeys.keys660_v8;seed=FirmwareKeys.SEED_5A;break;
            case 0x380292f0:key=FirmwareKeys.keys660_v4;seed=FirmwareKeys.SEED_5A;break;
            case 0x380293f0:key=FirmwareKeys.keys660_v5;seed=FirmwareKeys.SEED_5A;break;
            default: throw new IOException(String.format("Unsupported PRX tag %08x",tag));
        }
        byte[] derivation=new byte[144];
        for(int i=0;i<9;i++) { System.arraycopy(key,0,derivation,i*16,16);derivation[i*16]=(byte)i; }
        byte[] schedule=aes(seed,derivation);
        byte[] header=new byte[336];
        int[][] fields={{0xd0,0,92},{0x140,92,16},{0x12c,108,20},{0x80,128,48},{0xc0,176,16},{0xb0,192,16},{0,208,128}};
        for(int[] field:fields)System.arraycopy(prx,field[0],header,field[1],field[2]);
        byte[] clear=aes(seed,slice(header,92,96));System.arraycopy(clear,0,header,92,96);
        byte[] expected=slice(header,108,20),nonce=slice(header,92,16);
        Arrays.fill(header,24,112,(byte)0);System.arraycopy(nonce,0,header,112,16);
        System.arraycopy(header,0,header,4,4);System.arraycopy(schedule,0,header,8,16);
        try {
            if(!MessageDigest.isEqual(MessageDigest.getInstance("SHA-1").digest(slice(header,4,332)),expected))
                throw new IOException("PRX header SHA-1 mismatch");
        } catch(NoSuchAlgorithmException e) {throw new IOException(e);}
        byte[] encryptedKeys=slice(header,128,64);
        for(int i=0;i<64;i++)encryptedKeys[i]^=schedule[16+i];
        byte[] decoded=aes(seed,encryptedKeys),packet=new byte[prx.length-64];
        for(int i=0;i<64;i++)packet[i]=(byte)(decoded[i]^schedule[80+i]);
        packet[0x60]=1;System.arraycopy(prx,0xb0,packet,0x70,16);
        System.arraycopy(prx,0,packet,0x90,128);System.arraycopy(prx,0x150,packet,0x110,prx.length-0x150);
        return privatePacket(packet);
    }
    public static byte[] decryptTable661(byte[] table) throws IOException {
        try {
            Cipher cipher=Cipher.getInstance("DES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(hex("a664c8f8fd9d4498"),"DES"),new IvParameterSpec(hex("db4e7941f59730ad")));
            return decryptModule(cipher.doFinal(table));
        } catch(GeneralSecurityException e) {throw new IOException("File table DES failed",e);}
    }
    private static byte[] privatePacket(byte[] packet) throws IOException {
        if(word(packet,0x60)!=1 || packet[0x64]!=0) throw new IOException("Unsupported KIRK private envelope mode");
        int length=word(packet,0x70),offset=word(packet,0x74);
        if(length<=0 || length>32*1024*1024 || offset<0 || offset>32*1024*1024) throw new IOException("Invalid private payload size");
        int padded=(length+15)&~15;
        byte[] content=slice(packet,0x90+offset,padded);
        byte[] keys=aes(hex("98c940975c1d10e87fe60ea3fd03a8ba"),slice(packet,0,32));
        byte[] macKey=slice(keys,16,16);
        if(!MessageDigest.isEqual(cmac(macKey,slice(packet,0x60,48)),slice(packet,0x20,16)))
            throw new IOException("KIRK header authentication failed");
        if(!MessageDigest.isEqual(cmac(macKey,slice(packet,0x60,48+offset+padded)),slice(packet,0x30,16)))
            throw new IOException("KIRK payload authentication failed");
        return Arrays.copyOf(aes(slice(keys,0,16),content),length);
    }
    public static byte[] demanglePsar3(byte[] envelope) throws IOException {
        byte[] result=envelope.clone();
        byte[] header=aes(FirmwareKeys.SEED_55,slice(envelope,0x20,0x130));
        System.arraycopy(header,0,result,0x20,header.length);
        return decryptLegacy(result);
    }
    public static int psar3EnvelopeSize(byte[] header) throws IOException {
        byte[] clear=aes(FirmwareKeys.SEED_55,slice(header,0x20,0x130));
        int size=word(clear,0x90);
        if(size<=0 || size>8*1024*1024) throw new IOException("Invalid PSAR envelope length");
        return 0x150+((size+15)&~15);
    }
}
