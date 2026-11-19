package dev.sunmibridge;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal binary IPP 1.1/2.0 codec; independent of Android for host-side tests. */
public final class Ipp {
    private Ipp() {}

    public static final int PRINT_JOB=0x0002, VALIDATE_JOB=0x0004, CANCEL_JOB=0x0008,
            GET_JOB_ATTRIBUTES=0x0009, GET_JOBS=0x000A, GET_PRINTER_ATTRIBUTES=0x000B;
    public static final int OK=0, NOT_FOUND=0x0406, FORMAT_NOT_SUPPORTED=0x040A,
            INTERNAL_ERROR=0x0500, OPERATION_NOT_SUPPORTED=0x0501;

    public static final int OPERATION=0x01, JOB=0x02, END=0x03, PRINTER=0x04;
    public static final int INTEGER=0x21, BOOLEAN=0x22, ENUM=0x23,
            OCTET_STRING=0x30, RESOLUTION=0x32, RANGE=0x33, BEGIN_COLLECTION=0x34,
            END_COLLECTION=0x37, TEXT=0x41, NAME=0x42, KEYWORD=0x44,
            URI=0x45, CHARSET=0x47, LANGUAGE=0x48, MIME=0x49, MEMBER_NAME=0x4A;

    public static final class Request {
        public final int major, minor, operation, id;
        public final Map<String,List<byte[]>> attrs = new HashMap<>();
        Request(int ma, int mi, int op, int id) {
            this.major=ma; this.minor=mi; this.operation=op; this.id=id;
        }
        public String string(String key, String fallback) {
            List<byte[]> all = attrs.get(key);
            return all==null || all.isEmpty() ? fallback : new String(all.get(0), StandardCharsets.UTF_8);
        }
        public int number(String key, int fallback) {
            List<byte[]> all = attrs.get(key);
            if (all==null || all.isEmpty() || all.get(0).length!=4) return fallback;
            byte[] b=all.get(0);
            return (b[0]&255)<<24|(b[1]&255)<<16|(b[2]&255)<<8|(b[3]&255);
        }
    }

    public static Request read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int major = in.readUnsignedByte(), minor=in.readUnsignedByte();
        int operation=in.readUnsignedShort(), id=in.readInt();
        if (major<1 || major>2) throw new IOException("Unsupported IPP version");
        Request request=new Request(major,minor,operation,id);
        String lastName="";
        int entries=0;
        while (true) {
            if (++entries>3000) throw new IOException("Too many IPP attributes");
            int tag=in.readUnsignedByte();
            if (tag==END) return request;
            if (tag<=0x0f) { lastName=""; continue; }
            int nameLength=in.readUnsignedShort();
            if (nameLength>2048) throw new IOException("IPP attribute name too long");
            String name=lastName;
            if (nameLength>0) {
                byte[] nameData=new byte[nameLength]; in.readFully(nameData);
                name=new String(nameData,StandardCharsets.US_ASCII);
                lastName=name;
            }
            int valueLength=in.readUnsignedShort();
            if (valueLength>32768) throw new IOException("IPP value too large");
            byte[] data=new byte[valueLength]; in.readFully(data);
            request.attrs.computeIfAbsent(name,k->new ArrayList<>()).add(data);
        }
    }

    public static final class Reply {
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final DataOutputStream out=new DataOutputStream(bytes);
        private String lastName=null;
        public Reply(Request request,int status) throws IOException {
            // Reply with the version used by the client. Hardcoding IPP/1.1
            // makes CUPS ipptool and driverless macOS discovery reject IPP/2.0.
            out.writeByte(request.major); out.writeByte(request.minor);
            out.writeShort(status); out.writeInt(request.id);
            group(OPERATION);
            string(CHARSET,"attributes-charset","utf-8");
            string(LANGUAGE,"attributes-natural-language","en");
        }
        public Reply group(int tag) throws IOException {out.writeByte(tag);lastName=null;return this;}
        public Reply value(int tag,String name,byte[] value) throws IOException {
            out.writeByte(tag);
            boolean repeated=name!=null && name.equals(lastName);
            byte[] nameBytes=(name==null || repeated ? "" : name).getBytes(StandardCharsets.UTF_8);
            out.writeShort(nameBytes.length);out.write(nameBytes);
            out.writeShort(value.length);out.write(value);
            if (name!=null && !name.isEmpty()) lastName=name;
            return this;
        }
        public Reply string(int tag,String name,String data) throws IOException {
            return value(tag,name,data.getBytes(StandardCharsets.UTF_8));
        }
        public Reply integer(int tag,String name,int v) throws IOException {
            return value(tag,name,new byte[]{(byte)(v>>24),(byte)(v>>16),(byte)(v>>8),(byte)v});
        }
        public Reply bool(String name,boolean v) throws IOException {return value(BOOLEAN,name,new byte[]{(byte)(v?1:0)});}
        public Reply resolution(String name,int dpi) throws IOException {
            return value(RESOLUTION,name,new byte[]{0,0,(byte)(dpi>>8),(byte)dpi,0,0,(byte)(dpi>>8),(byte)dpi,3});
        }
        public Reply range(String name,int low,int high) throws IOException {
            return value(RANGE,name,new byte[]{(byte)(low>>24),(byte)(low>>16),(byte)(low>>8),(byte)low,
                    (byte)(high>>24),(byte)(high>>16),(byte)(high>>8),(byte)high});
        }
        private Reply member(String name) throws IOException {return string(MEMBER_NAME,null,name);}
        private Reply memberInt(String name,int number) throws IOException {
            member(name);return integer(INTEGER,null,number);
        }
        public Reply mediaCollection(String name, String media, int lengthHundredthsMm) throws IOException {
            value(BEGIN_COLLECTION,name,new byte[0]);
            member("media-key"); string(KEYWORD,null,media);
            member("media-size"); value(BEGIN_COLLECTION,null,new byte[0]);
            memberInt("x-dimension",5800);
            memberInt("y-dimension",lengthHundredthsMm);
            value(END_COLLECTION,null,new byte[0]);
            memberInt("media-left-margin",500);
            memberInt("media-right-margin",500);
            memberInt("media-top-margin",0);
            memberInt("media-bottom-margin",0);
            member("media-type");string(KEYWORD,null,"stationery");
            value(END_COLLECTION,null,new byte[0]);
            return this;
        }
        public byte[] finish() throws IOException {out.writeByte(END);out.flush();return bytes.toByteArray();}
    }
}
