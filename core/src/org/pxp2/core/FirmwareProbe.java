package org.pxp2.core;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Inspects the actual updater. A successful container extraction is never treated as a PSP boot. */
public final class FirmwareProbe {
    public static String inspect(PbpPackage.Extraction extraction) throws IOException {
        File updater = new File(extraction.directory,"DATA.PSP");
        byte[] header = new byte[336];
        try (DataInputStream in = new DataInputStream(new FileInputStream(updater))) { in.readFully(header); }
        String signature = new String(header,0,4,StandardCharsets.US_ASCII);
        int psarVersion;
        try (DataInputStream in = new DataInputStream(new FileInputStream(new File(extraction.directory,"DATA.PSAR")))) {
            if (in.readInt()!=0x50534152) throw new IOException("Invalid PSAR signature"); psarVersion=in.readUnsignedByte();
        }
        if ("~PSP".equals(signature)) {
            return "Firmware execution blocked before the CPU starts.\n"
                + "DATA.PSP is an encrypted PSP updater module.\nDATA.PSAR version: " + psarVersion + ".\n"
                + "Required next: PRX/PSAR decryption, decompression, flash file installation, module relocation/linking, and PSP kernel/hardware support.\n"
                + "Sony firmware instructions executed: 0. XMB has not booted.";
        }
        return "Unexpected updater signature; execution refused. No Sony firmware instructions executed.";
    }
}
