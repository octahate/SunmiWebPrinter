import dev.sunmibridge.Ipp;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class IppCodecSmokeTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();
        DataOutputStream o=new DataOutputStream(raw);
        o.writeByte(1);o.writeByte(1);
        o.writeShort(Ipp.PRINT_JOB);o.writeInt(123);
        o.writeByte(Ipp.OPERATION);
        writeString(o,Ipp.CHARSET,"attributes-charset","utf-8");
        writeString(o,Ipp.MIME,"document-format","application/pdf");
        o.writeByte(Ipp.JOB);
        writeString(o,Ipp.NAME,"job-name","Test receipt");
        o.writeByte(Ipp.END);o.write("%PDF-DATA".getBytes(StandardCharsets.US_ASCII));o.flush();
        ByteArrayInputStream in=new ByteArrayInputStream(raw.toByteArray());
        Ipp.Request request=Ipp.read(in);
        check(request.major==1 && request.minor==1,"IPP version");
        check(request.operation==Ipp.PRINT_JOB && request.id==123,"Request header");
        check("application/pdf".equals(request.string("document-format","")),"MIME");
        check("Test receipt".equals(request.string("job-name","")),"Job name");
        byte[] doc=new byte[9];check(in.read(doc)==9,"Request payload length");
        check("%PDF-DATA".equals(new String(doc,StandardCharsets.US_ASCII)),"Document remaining");
        Ipp.Reply w=new Ipp.Reply(request,Ipp.OK).group(Ipp.PRINTER);
        w.string(Ipp.NAME,"printer-name","SUNMI V2 Receipt");
        w.mediaCollection("media-col-default","custom_58x200mm_58x200mm",20000);
        w.resolution("printer-resolution-supported",203);
        byte[] response=w.finish();
        check(response[0]==1 && response[1]==1 && response[2]==0 && response[3]==0,"IPP response header");
        check(response[response.length-1]==Ipp.END,"IPP end marker");
        ByteArrayInputStream responseReader=new ByteArrayInputStream(response);
        Ipp.Request parsed=Ipp.read(responseReader);
        check(parsed.id==123,"Response request ID");
        check("SUNMI V2 Receipt".equals(parsed.string("printer-name","")),"Response name");
        check(parsed.attrs.containsKey("media-col-default"),"Paper collection");
        System.out.println("PASS: IPP attributes and payload binary codec");
    }
    static void writeString(DataOutputStream o,int type,String name,String value)throws IOException {
        byte[] n=name.getBytes(StandardCharsets.US_ASCII);
        byte[] v=value.getBytes(StandardCharsets.UTF_8);
        o.writeByte(type);o.writeShort(n.length);o.write(n);o.writeShort(v.length);o.write(v);
    }
}
