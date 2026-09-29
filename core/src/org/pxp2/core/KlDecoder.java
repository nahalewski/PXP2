package org.pxp2.core;

import java.io.IOException;
import java.util.Arrays;

/** Bounded Java implementation of the KL3E/KL4E format reverse engineered by artart78.
 * Adapted from the independent PSARDumper research; see CRYPTO-NOTICES.md. GPL-3.0-or-later. */
public final class KlDecoder {
    private final byte[] input;
    private int position=9;
    private long code,range=0xffffffffL,operations;
    private KlDecoder(byte[] input,int max) throws IOException {
        this.input=input;operations=(long)input.length*128+(long)max*64;
        if(input.length<9)throw new IOException("Truncated KL header");
        for(int i=5;i<9;i++)code=(code<<8)|(input[i]&255);
    }
    private int next() throws IOException {
        if(position>=input.length)throw new IOException("Truncated KL bitstream");
        return input[position++]&255;
    }
    private void tick() throws IOException {if(--operations<0)throw new IOException("KL operation limit");}
    private int bit(int[] probs,int index,int decay,int bonus) throws IOException {
        tick();if(index<0 || index>=probs.length)throw new IOException("Invalid KL context");
        int probability=probs[index];long bound;
        if((range>>>24)==0) {code=((code<<8)|next())&0xffffffffL;bound=range*probability;range=(range<<8)&0xffffffffL;}
        else bound=(range>>>8)*probability;
        probs[index]=probability-(probability>>>decay);
        if(code>=bound) {code-=bound;range-=bound;return 0;}
        range=bound;probs[index]+=bonus;return 1;
    }
    private int uniform(boolean normalize) throws IOException {
        tick();
        if(normalize && (range>>>24)==0) {code=((code<<8)|next())&0xffffffffL;range=(range<<7)&0xffffffffL;}
        else range>>>=1;
        if(code>=range) {code-=range;return 0;}return 1;
    }
    private int literal(int[] probs,int written,int previous,int shift) throws IOException {
        int base=((((written&7)<<8)|(previous&255))>>>shift & 7)*255-1;
        int value=1;while(value<256)value=(value<<1)|bit(probs,base+value,3,31);
        return value&255;
    }
    private static int[] probabilities(int size,int value) {int[] p=new int[size];Arrays.fill(p,value);return p;}
    public static byte[] decode(byte[] input,int expected) throws IOException {
        if(expected<1 || expected>8*1024*1024)throw new IOException("KL output limit");
        int magic=FirmwareCrypto.word(input,0);boolean kl4=magic==0x45344c4b;
        if(!kl4 && magic!=0x45334c4b)throw new IOException("Invalid KL signature");
        KlDecoder d=new KlDecoder(input,expected);
        if((input[4]&128)!=0) {
            if(d.code!=expected || input.length-9<expected)throw new IOException("Invalid KL direct length");
            return Arrays.copyOfRange(input,9,9+expected);
        }
        int probability=128-(((input[4]>>>3)&3)<<4),shift=input[4]&7;
        int[] literals=probabilities(2040,probability),lengthBits=probabilities(64,probability),lengths=probabilities(256,probability),distanceBits=probabilities(304,probability),distances=probabilities(144,probability);
        byte[] output=new byte[expected];int written=1,previous=d.literal(literals,0,0,shift),state=0;
        output[0]=(byte)previous;
        while(true) {
            if(d.bit(lengthBits,state,4,15)==0) {
                state=Math.max(0,state-1);
                if(written>=expected)throw new IOException("KL literal exceeds output");
                previous=d.literal(literals,written,previous,shift);output[written++]=(byte)previous;continue;
            }
            int bits=-1;
            while(bits<6) {state+=8;if(d.bit(lengthBits,state,4,15)==0)break;bits++;}
            int count=1,limit=64,distBase=bits;
            if(bits>=0) {
                int context=(bits<<5)|(((written&3)<<(bits+3))&24)|(state&7);
                if(bits>=3) {
                    count=2+d.bit(lengths,context+24,3,31);
                    if(bits>3) {
                        count=(count<<1)|d.bit(lengths,context+24,3,31);
                        if(bits>4)count=(count<<1)|d.uniform(true);
                        for(int i=5;i<bits;i++)count=(count<<1)|d.uniform(false);
                    }
                }
                int low=d.bit(lengths,context,3,31);count=(count<<1)|low;
                if(bits<=0 && low!=0) {limit=kl4?256:128;distBase=56+bits;}
                if(bits>0) {
                    count=(count<<1)|d.bit(lengths,context+8,3,31);
                    if(bits!=1) {
                        count=(count<<1)|d.bit(lengths,context+16,3,31);
                        if(count==255) {
                            if(written!=expected)throw new IOException("KL expanded size mismatch: "+written+" != "+expected);
                            return output;
                        }
                    }
                    distBase=56+bits;limit=kl4?256:128;
                }
            }
            int power=8,distContext,dist=0;boolean zero=false;
            while(true) {
                int index=distBase+power-7;power<<=1;distContext=power-limit;
                if(d.bit(distanceBits,index,3,31)==0) {
                    if(distContext>=0) {if(distContext==0)zero=true;else distContext-=8;break;}
                } else {power+=8;if(distContext>=0)break;}
            }
            if(!zero) {
                int n=distContext/8;dist=1;
                if(n>=3) {
                    dist=2+d.bit(distances,distContext+3,3,31);
                    if(n>3) {
                        dist=(dist<<1)|d.bit(distances,distContext+3,3,31);
                        if(n>4) {dist=(dist<<1)|d.uniform(true);n--;}
                        while(n>4) {dist=(dist<<1)|d.uniform(false);n--;}
                    }
                }
                dist<<=1;int low=d.bit(distances,distContext,3,31);
                if(low!=0 && n>0)dist++;if(low==0 && n<=0)dist--;
                if(n>0) {
                    dist<<=1;low=d.bit(distances,distContext+1,3,31);
                    if(low!=0 && n!=1)dist++;if(low==0 && n==1)dist--;
                    if(n!=1) {dist<<=1;if(d.bit(distances,distContext+2,3,31)==0)dist--;}
                }
            }
            if(dist<0 || dist>=written || count+1>expected-written)throw new IOException("KL backreference outside output");
            for(int i=0;i<=count;i++)output[written+i]=output[written+i-dist-1];
            written+=count+1;previous=output[written-1]&255;state=6+((written-1)&1);
        }
    }
}
