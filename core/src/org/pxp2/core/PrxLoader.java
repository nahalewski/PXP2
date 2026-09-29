package org.pxp2.core;

import java.io.IOException;
import java.util.*;

/** Original PSP PRX mapper and type-B relocator. Format references: PSPDEV PRXTool and uOFW. */
public final class PrxLoader {
    public static final class Symbol {
        public final String library;public final int nid,address;
        Symbol(String library,int nid,int address){this.library=library;this.nid=nid;this.address=address;}
        public String key(){return library+":"+String.format(Locale.ROOT,"%08x",nid);}
    }
    public static final class Module {
        public String name;public int base,entry,gp,size,relocations,unlinkedVariables,attributes,version;
        /** module_start/module_stop from the export "syslib" (0 when absent) and thread parameters. */
        public int moduleStart,moduleStop,startPriority,startStack,startAttributes;
        public int textSize;
        public final List<Symbol> imports=new ArrayList<>(),exports=new ArrayList<>();
        /** Variable exports (address = variable location) and imports (address = relocation list). */
        public final List<Symbol> exportVariables=new ArrayList<>(),importVariables=new ArrayList<>();
    }
    public static final int NID_MODULE_START=0xd632acdb,NID_MODULE_STOP=0xcee8593c,NID_START_THREAD_PARAMETER=0x0f7c276c,NID_MODULE_BOOTSTART=0xd3744be0;
    private static final class Segment {int type,offset,virtual,physical,fileSize,size,flags;}
    private static int word(byte[] b,int p)throws IOException{return FirmwareCrypto.word(b,p);}
    private static int half(byte[] b,int p)throws IOException {
        if(p<0||p>b.length-2)throw new IOException("Truncated PRX halfword");return (b[p]&255)|((b[p+1]&255)<<8);
    }
    private static void require(boolean b,String message)throws IOException{if(!b)throw new IOException(message);}
    private static void range(int at,int size,int end)throws IOException{require(at>=0 && size>=0 && at<=end-size,"PRX range outside file");}
    public static String string(Memory memory,int address,int max)throws IOException {
        if(address==0)return "";StringBuilder text=new StringBuilder();
        for(int i=0;i<max;i++){int c=memory.read8(address+i);if(c==0)return text.toString();require(c>=32 && c<127,"Invalid PRX string");text.append((char)c);}
        throw new IOException("Unterminated PRX string");
    }
    /** Address (sh_addr) of the named ELF section, or 0. */
    static int sectionAddress(byte[] b,String name)throws IOException{return sectionField(b,name,12);}
    /** A field of the named ELF section header (12 = sh_addr, 16 = sh_offset), or 0. */
    private static int sectionField(byte[] b,String name,int field)throws IOException {
        int shoff=word(b,32),size=half(b,46),count=half(b,48),strndx=half(b,50);
        if(shoff<=0 || size<40 || count==0 || strndx>=count)return 0;range(shoff,count*size,b.length);
        int strOff=word(b,shoff+strndx*size+16);
        for(int i=0;i<count;i++){
            int h=shoff+i*size,n=strOff+word(b,h);if(n<0 || n>=b.length)continue;
            int e=n;while(e<b.length && b[e]!=0)e++;
            if(new String(b,n,e-n,java.nio.charset.StandardCharsets.US_ASCII).equals(name))return word(b,h+field);
        }
        return 0;
    }
    /**
     * Module attributes (sceModuleInfo +0) of a ~PSP envelope, a PRX or a static ELF, without loading it; -1 when unknown
     * (a malformed file). Bit 0x1000 marks a kernel-mode module.
     */
    public static int moduleAttributes(byte[] b) {
        try {
            if(b.length>=0x150 && word(b,0)==0x5053507e)return half(b,4);
            if(b.length<52 || word(b,0)!=0x464c457f)return -1;
            if(half(b,16)==2){int at=sectionField(b,".rodata.sceModuleInfo",16);return at>0 && at+2<=b.length?half(b,at):-1;}
            if(half(b,16)!=0xffa0)return -1;
            int ph=word(b,28),info=(word(b,ph+12)&0x7fffffff);
            return info>0 && info+2<=b.length?half(b,info):-1;
        } catch(IOException e){return -1;}
    }
    /** Lowest and highest (exclusive) load address of a static ELF's PT_LOAD segments; {0,0} for a PRX. */
    public static int[] staticRange(byte[] b) {
        try { return staticRangeOrThrow(b); } catch(IOException e) { return new int[]{0,0}; }
    }
    private static int[] staticRangeOrThrow(byte[] b)throws IOException {
        if(b.length<52 || half(b,16)!=2)return new int[]{0,0};
        int ph=word(b,28),count=half(b,44),lo=Integer.MAX_VALUE,hi=0;
        for(int i=0;i<count;i++){int p=ph+i*32;if(p+32>b.length || word(b,p)!=1)continue;lo=Math.min(lo,word(b,p+8));hi=Math.max(hi,word(b,p+8)+word(b,p+20));}
        return lo==Integer.MAX_VALUE?new int[]{0,0}:new int[]{lo,hi};
    }
    public static Module load(byte[] bytes,Memory memory,int base)throws IOException {
        require(bytes.length>=52 && word(bytes,0)==0x464c457f && bytes[4]==1 && bytes[5]==1 && bytes[6]==1,"Invalid PRX ELF header");
        require((half(bytes,16)==0xffa0 || half(bytes,16)==2) && half(bytes,18)==8,"Not a MIPS PSP PRX or static ELF");
        // ET_EXEC (type 2): a static homebrew executable linked at a fixed address (usually 0x08900000). It is loaded at its
        // link addresses with no relocation (the caller passes base 0 after reserving that range); everything else is a PRX.
        boolean exec=half(bytes,16)==2;
        // A PRX needs only one PT_LOAD: homebrew linked with a single text+data segment carries its relocations in
        // 0x700000a0 sections, not in a packed 0x700000a1 program header.
        int ph=word(bytes,28),count=half(bytes,44);require(count>=1 && count<=(exec?8:5) && half(bytes,42)==32,"Invalid PRX program headers");range(ph,count*32,bytes.length);
        Segment[] segments=new Segment[count];Module module=new Module();module.base=base;
        for(int i=0;i<count;i++) {
            int p=ph+i*32;Segment s=new Segment();segments[i]=s;
            s.type=word(bytes,p);s.offset=word(bytes,p+4);s.virtual=word(bytes,p+8);s.physical=word(bytes,p+12);
            s.fileSize=word(bytes,p+16);s.size=word(bytes,p+20);s.flags=word(bytes,p+24);range(s.offset,s.fileSize,bytes.length);
            if(s.type==1 && exec) {
                require(s.size>=s.fileSize && (s.virtual&0xffffffffL)>=Memory.BASE && (s.virtual&0xffffffffL)+s.size<=(long)Memory.BASE+memory.size,"Static ELF segment outside user RAM");
                module.size=Math.max(module.size,s.virtual+s.size);
            } else if(s.type==1) {
                require(s.virtual>=0 && s.size>=s.fileSize && s.size<=memory.size && (long)s.virtual+s.size<=memory.size,"Invalid PRX RAM segment");
                require((long)base+s.virtual>=Memory.BASE && (long)base+s.virtual+s.size<=(long)Memory.BASE+memory.size,"PRX allocation outside RAM");
                for(int j=0;j<i;j++)if(segments[j].type==1)require(s.virtual+s.size<=segments[j].virtual || segments[j].virtual+segments[j].size<=s.virtual,"Overlapping PRX segments");
                module.size=Math.max(module.size,s.virtual+s.size);
            } else require(exec || s.type==0x700000a1,"Unsupported PRX program header");
        }
        // A PRX must begin with its loadable text segment (it carries sceModuleInfo); a static ELF (ET_EXEC) may lead with
        // a non-loadable segment (e.g. PT_MIPS_ABIFLAGS/REGINFO 0x7000000x) and locates sceModuleInfo via the section table.
        require(exec || segments[0].type==1,"PRX first segment must load");
        for(Segment s:segments)if(s.type==1) {
            for(int i=0;i<s.fileSize;i++)memory.write8(base+s.virtual+i,bytes[s.offset+i]);
            for(int i=s.fileSize;i<s.size;i++)memory.write8(base+s.virtual+i,0);
        }
        int info;
        if(exec) {
            // Static executables locate sceModuleInfo through the section table (.rodata.sceModuleInfo).
            info=sectionAddress(bytes,".rodata.sceModuleInfo");
            require(info!=0,"Static ELF has no .rodata.sceModuleInfo section");
        } else {
            boolean packed=false;
            for(int i=0;i<count;i++)if(segments[i].type==0x700000a1){module.relocations+=relocate(bytes,segments,i,memory,base);packed=true;}
            if(!packed)module.relocations+=relocateSections(bytes,segments,memory,base);
            int infoOffset=(segments[0].physical&0x7fffffff)-segments[0].offset;
            require(infoOffset>=0 && infoOffset+52<=segments[0].fileSize,"Invalid PRX module information");
            info=base+segments[0].virtual+infoOffset;
        }
        module.attributes=memory.read16(info);module.version=memory.read16(info+2);
        module.name=string(memory,info+4,28);module.gp=memory.read32(info+32);module.entry=word(bytes,24)==-1?0:base+word(bytes,24);
        module.textSize=segments[0].type==1?segments[0].size:0;
        for(Segment s:segments)if(s.type==1){module.textSize=s.size;break;} // first loadable segment (seg0 may be non-loadable in a static ELF)
        require(module.entry==0 || ((module.entry&3)==0 && module.entry>=base && module.entry<base+module.size),"PRX entry outside allocation"); // 0xffffffff: library without entry
        symbols(memory,memory.read32(info+36),memory.read32(info+40),false,module);
        symbols(memory,memory.read32(info+44),memory.read32(info+48),true,module);
        module.moduleStart=module.entry;
        for(Symbol s:module.exports)if(s.library.isEmpty()){
            if(s.nid==NID_MODULE_START||s.nid==NID_MODULE_BOOTSTART)module.moduleStart=s.address;else if(s.nid==NID_MODULE_STOP)module.moduleStop=s.address;
        }
        for(Symbol s:module.exportVariables)if(s.library.isEmpty() && s.nid==NID_START_THREAD_PARAMETER && memory.valid(s.address,16) && memory.read32(s.address)>=3) {
            module.startPriority=memory.read32(s.address+4);module.startStack=memory.read32(s.address+8);module.startAttributes=memory.read32(s.address+12);
        }
        return module;
    }
    private static void symbols(Memory memory,int start,int end,boolean importing,Module module)throws IOException {
        require(start>=module.base && end>=start && end<=module.base+module.size && end-start<65536,"Invalid PRX symbol table range");
        for(int p=start;p<end;) {
            int words=memory.read8(p+8),variables=memory.read8(p+9),functions=memory.read8(p+10)|(memory.read8(p+11)<<8);
            require(words>=(importing?5:4) && p+words*4<=end && functions<=4096,"Invalid PRX symbol descriptor");
            String library=string(memory,memory.read32(p),128);
            int nids=memory.read32(p+12),addresses=importing?memory.read32(p+16):nids+(variables+functions)*4;
            for(int i=0;i<functions;i++) {
                Symbol symbol=new Symbol(library,memory.read32(nids+i*4),importing?addresses+i*8:memory.read32(addresses+i*4));
                (importing?module.imports:module.exports).add(symbol);
            }
            if(importing) {
                int table=words>=6?memory.read32(p+20):0;
                if(variables>0 && table!=0)for(int i=0;i<variables;i++)module.importVariables.add(new Symbol(library,memory.read32(table+i*8+4),memory.read32(table+i*8)));
                else module.unlinkedVariables+=variables;
            } else for(int i=0;i<variables;i++)module.exportVariables.add(new Symbol(library,memory.read32(nids+(functions+i)*4),memory.read32(addresses+(functions+i)*4)));
            p+=words*4;
        }
    }
    /**
     * Older PRXs (e.g. 2005-2006 retail games) carry no packed relocation segment; their relocations are ELF sections of
     * type 0x700000a0 holding Elf32_Rel pairs. r_info packs type (bits 0-7), the segment the offset is relative to
     * (bits 8-15) and the segment whose load address is added (bits 16-23). HI16 entries are resolved with the next
     * LO16, whose signed low half forms the full addend (the usual MIPS AHL rule).
     */
    private static int relocateSections(byte[] data,Segment[] segments,Memory memory,int base)throws IOException {
        int shoff=word(data,32),shentsize=half(data,46),shnum=half(data,48),count=0;
        if(shoff==0||shnum==0)return 0;
        require(shentsize>=40 && shnum<4096,"Invalid PRX section headers");range(shoff,shnum*shentsize,data.length);
        for(int s=0;s<shnum;s++) {
            int h=shoff+s*shentsize;if(word(data,h+4)!=0x700000a0)continue;
            int offset=word(data,h+16),size=word(data,h+20);range(offset,size,data.length);
            List<Integer> his=new ArrayList<>();
            for(int p=offset;p+8<=offset+size;p+=8) {
                int rOffset=word(data,p),info=word(data,p+4),type=info&255,ofs=(info>>>8)&255,adr=(info>>>16)&255;
                require(ofs<segments.length && adr<segments.length && segments[ofs].type==1 && segments[adr].type==1,"Invalid PRX relocation segment");
                // R_MIPS_32 may target unaligned data (packed structures in homebrew); code relocations are always aligned.
                require(rOffset>=0 && ((rOffset&3)==0 || (info&255)==2) && rOffset<=segments[ofs].size-4,"Relocation target outside segment");
                if((rOffset&3)!=0){int a=base+segments[ofs].virtual+rOffset,v=0;for(int i=3;i>=0;i--)v=(v<<8)|memory.read8(a+i);v+=base+segments[adr].virtual;for(int i=0;i<4;i++)memory.write8(a+i,v>>>(8*i));count++;continue;}
                int address=base+segments[ofs].virtual+rOffset,original=memory.read32(address),referent=base+segments[adr].virtual,result;
                switch(type) {
                    case 0:continue;
                    case 7:continue;                                                                     // R_MIPS_GPREL16: gp moves with the module, so gp-relative offsets are unchanged
                    case 2:result=original+referent;break;                                              // R_MIPS_32
                    case 4:result=(original&0xfc000000)|((original+(referent>>>2))&0x03ffffff);break;    // R_MIPS_26
                    case 5:his.add(address);continue;                                                    // R_MIPS_HI16: wait for LO16
                    case 6: {                                                                            // R_MIPS_LO16
                        int lo=(short)original;
                        for(int hi:his){int w=memory.read32(hi),full=(w<<16)+lo+referent;memory.write32(hi,(w&0xffff0000)|(((full+0x8000)>>>16)&0xffff));count++;}
                        his.clear();result=(original&0xffff0000)|((lo+referent)&0xffff);break;
                    }
                    default:throw new IOException("Unsupported PRX section relocation type "+type);
                }
                memory.write32(address,result);count++;
            }
        }
        return count;
    }
    private static int relocate(byte[] data,Segment[] segments,int index,Memory memory,int base)throws IOException {
        Segment rel=segments[index];int p=rel.offset,end=p+rel.fileSize;
        require(rel.fileSize>=8 && half(data,p)==0,"Invalid compressed relocation header");
        int flagBits=data[p+2]&255,typeBits=data[p+3]&255,segBits=1;while((1<<segBits)<index)segBits++;
        require(flagBits>0 && typeBits>0 && flagBits+typeBits+segBits<16,"Invalid relocation bit widths");
        int flags=p+4,flagCount=data[flags]&255;range(flags,flagCount,end);
        int types=flags+flagCount;range(types,1,end);int typeCount=data[types]&255;range(types,typeCount,end);
        p=types+typeCount;int offset=0,offsetSegment=-1,addend=0,lastType=-1,count=0;
        while(p<end) {
            range(p,2,end);int command=half(data,p);p+=2;
            int fi=command&((1<<flagBits)-1),si=(command>>>flagBits)&((1<<segBits)-1);
            require(fi<flagCount && si<index && segments[si].type==1,"Invalid relocation index");
            int flag=data[flags+fi]&255;
            if((flag&1)==0) {
                offsetSegment=si;
                if((flag&6)==0)offset=command>>>(flagBits+segBits);
                else if((flag&6)==4){range(p,4,end);offset=word(data,p);p+=4;}
                else throw new IOException("Invalid relocation base command");
                continue;
            }
            int ti=(command>>>(flagBits+segBits))&((1<<typeBits)-1);require(ti<typeCount && offsetSegment>=0,"Invalid relocation type index");
            int type=data[types+ti]&255,shift=flagBits+segBits+typeBits;
            switch(flag&6) {
                case 0:offset+=(short)command>>shift;break;
                case 2:range(p,2,end);offset+=(((short)command>>shift)<<16)|half(data,p);p+=2;break;
                case 4:range(p,4,end);offset=word(data,p);p+=4;break;
                default:throw new IOException("Invalid relocation delta");
            }
            switch(flag&56) {
                case 0:addend=0;break;
                case 8:if(lastType!=4)addend=0;break;
                case 16:range(p,2,end);addend=(short)half(data,p);p+=2;break;
                default:throw new IOException("Unsupported relocation addend");
            }
            lastType=type;Segment target=segments[offsetSegment];require(offset>=0 && offset<=target.fileSize-4 && (offset&3)==0,"Relocation target outside segment");
            int address=base+target.virtual+offset,original=memory.read32(address),referent=base+segments[si].virtual,result;
            switch(type) {
                case 0:continue;
                case 1:case 5:result=(original&0xffff0000)|((original+referent)&65535);break;
                case 2:result=original+referent;break;
                case 3:case 6:case 7:
                    int opcode=type==6?0x08000000:type==7?0x0c000000:original&0xfc000000;
                    result=opcode|((((original&0x03ffffff)+(referent>>>2)))&0x03ffffff);break;
                case 4:result=(original&0xffff0000)|(((((original&65535)<<16)+addend+referent+0x8000)>>>16)&65535);break;
                default:throw new IOException("Unsupported compressed relocation type "+type);
            }
            memory.write32(address,result);count++;
        }
        return count;
    }
}
