package dev.sunmibridge;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class IppServer implements Closeable {
    public static final int PORT=8631;
    public static final String[] MEDIA={
            "custom_58x50mm_58x50mm","custom_58x100mm_58x100mm",
            "custom_58x150mm_58x150mm","custom_58x200mm_58x200mm",
            "custom_58x300mm_58x300mm"};
    private static final int[] LENGTHS={5000,10000,15000,20000,30000};
    private static final long MAX_BODY=30*1024*1024L;
    private final SunmiDriver driver;
    private final File spool;
    private final AtomicBoolean alive=new AtomicBoolean(false);
    private final AtomicInteger nextJob=new AtomicInteger(1);
    private final Map<Integer,Job> jobs=new ConcurrentHashMap<>();
    private final ExecutorService connections=Executors.newFixedThreadPool(4);
    private final ExecutorService printQueue=Executors.newSingleThreadExecutor();
    private Thread acceptThread;
    private ServerSocket socket;

    public static class Job {
        public final int id;
        public final String name, format, uri;
        public final File spoolFile;
        public volatile int state=3;
        public volatile String reason="none";
        public volatile boolean canceled;
        Job(int id,String name,String format,File file,String uri) {
            this.id=id;this.name=name;this.format=format;this.spoolFile=file;this.uri=uri;
        }
    }

    public IppServer(SunmiDriver driver,File cacheDir) {
        this.driver=driver;
        this.spool=new File(cacheDir,"print-jobs");
        if (!spool.exists() && !spool.mkdirs()) throw new IllegalStateException("Cannot create spool");
    }
    public synchronized void start() throws IOException {
        if (alive.get()) return;
        socket=new ServerSocket(PORT,8);
        alive.set(true);
        acceptThread=new Thread(()->{
            while(alive.get()) try {
                Socket client=socket.accept();
                client.setSoTimeout(25000);
                connections.execute(()->handle(client));
            } catch(IOException e) {if(alive.get()) Log.e("SunmiIPP","accept",e);}
            catch(java.util.concurrent.RejectedExecutionException ignored) {}
        },"ipp-listen");
        acceptThread.start();
    }
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        for(int i=0;i<8192;i++) {
            int c=in.read();if(c<0) return b.size()==0?null:b.toString("US-ASCII");
            if(c=='\n') {
                byte[] bytes=b.toByteArray();
                int len=bytes.length;
                if(len>0 && bytes[len-1]=='\r') len--;
                return new String(bytes,0,len,StandardCharsets.US_ASCII);
            }
            b.write(c);
        }
        throw new IOException("HTTP line too long");
    }
    private static final class LimitedInputStream extends InputStream {
        private final InputStream delegate;
        private long remaining;
        LimitedInputStream(InputStream d,long n){delegate=d;remaining=n;}
        @Override public int read()throws IOException {
            if(remaining<=0)return -1;
            int c=delegate.read();if(c>=0)remaining--;return c;
        }
        @Override public int read(byte[]b,int off,int len)throws IOException {
            if(remaining<=0)return -1;
            int n=delegate.read(b,off,(int)Math.min(len,remaining));if(n>0)remaining-=n;return n;
        }
    }
    private static final class ChunkedInputStream extends InputStream {
        final InputStream in;
        long remaining=0,total=0;
        boolean finished;
        ChunkedInputStream(InputStream i){in=i;}
        private void next()throws IOException {
            String line=readLine(in);
            if(line==null)throw new EOFException("Missing chunk length");
            String hex=line.split(";",2)[0].trim();
            remaining=Long.parseLong(hex,16);
            total+=remaining;
            if(total>MAX_BODY)throw new IOException("Job too large");
            if(remaining==0){
                finished=true;
                String trailer;
                while((trailer=readLine(in))!=null && !trailer.isEmpty()) {}
            }
        }
        @Override public int read()throws IOException {
            byte[] b=new byte[1];return read(b,0,1)<0?-1:(b[0]&255);
        }
        @Override public int read(byte[] b,int off,int len)throws IOException {
            if(finished)return -1;
            if(remaining==0)next();
            if(finished)return -1;
            int n=in.read(b,off,(int)Math.min(len,remaining));
            if(n<0)throw new EOFException("Truncated chunk");
            remaining-=n;
            if(remaining==0){if(in.read()!='\r'||in.read()!='\n')throw new IOException("Bad chunk terminator");}
            return n;
        }
    }
    private void handle(Socket client) {
        try(Socket conn=client;InputStream in=new BufferedInputStream(conn.getInputStream());
            OutputStream out=new BufferedOutputStream(conn.getOutputStream())) {
            String first=readLine(in);if(first==null)return;
            String[] methodPath=first.split(" ");
            if(methodPath.length<2){send(out,400,"text/plain","Bad HTTP request".getBytes(StandardCharsets.UTF_8));return;}
            String method=methodPath[0],path=methodPath[1];
            boolean chunked=false,expect=false;
            long contentLength=-1;
            String header;
            while((header=readLine(in))!=null && !header.isEmpty()) {
                int colon=header.indexOf(':');if(colon<0)continue;
                String key=header.substring(0,colon).trim().toLowerCase(Locale.US);
                String value=header.substring(colon+1).trim().toLowerCase(Locale.US);
                if("content-length".equals(key))contentLength=Long.parseLong(value);
                if("transfer-encoding".equals(key)&&value.contains("chunked"))chunked=true;
                if("expect".equals(key)&&value.contains("100-continue"))expect=true;
            }
            if("GET".equals(method)) {
                if("/health".equals(path)){
                    String json="{\"device\":\"SUNMI V2\",\"paper_mm\":58,\"print_dots\":384,\"connected\":"+driver.isReady()+"}";
                    send(out,200,"application/json",json.getBytes(StandardCharsets.UTF_8));
                } else {
                    String html="<!doctype html><meta name='viewport' content='width=device-width'><h2>SUNMI V2 Network Printer</h2>"
                            +"<p>IPP URL: <code>ipp://"+NetworkUtils.localIp()+":"+PORT+"/ipp/print</code></p>"
                            +"<p>58 mm roll, 48 mm printable width, monochrome, 203 dpi.</p>";
                    send(out,200,"text/html; charset=utf-8",html.getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            if(!"POST".equals(method)||!"/ipp/print".equals(path)){
                send(out,404,"text/plain","Not found".getBytes(StandardCharsets.US_ASCII));return;
            }
            if(contentLength>MAX_BODY || (!chunked && contentLength<0)){
                send(out,413,"text/plain","Missing or excessive length".getBytes(StandardCharsets.US_ASCII));return;
            }
            if(expect){out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));out.flush();}
            InputStream body=chunked?new ChunkedInputStream(in):new LimitedInputStream(in,contentLength);
            Ipp.Request request=Ipp.read(body);
            byte[] answer=dispatch(request,body);
            send(out,200,"application/ipp",answer);
        } catch(Exception e) {Log.e("SunmiIPP","client error",e);}
    }
    private void send(OutputStream out,int status,String mime,byte[] payload)throws IOException {
        String text="HTTP/1.1 "+status+(status==200?" OK":" Error")+"\r\n"
                +"Content-Type: "+mime+"\r\nContent-Length: "+payload.length+"\r\n"
                +"Connection: close\r\nCache-Control: no-store\r\n\r\n";
        out.write(text.getBytes(StandardCharsets.US_ASCII));out.write(payload);out.flush();
    }
    private byte[] dispatch(Ipp.Request req,InputStream body)throws IOException {
        String base="ipp://"+NetworkUtils.localIp()+":"+PORT+"/ipp/print";
        switch(req.operation){
            case Ipp.GET_PRINTER_ATTRIBUTES:return printerAttrs(req,base);
            case Ipp.VALIDATE_JOB:return new Ipp.Reply(req,Ipp.OK).finish();
            case Ipp.PRINT_JOB:return acceptJob(req,body,base);
            case Ipp.GET_JOB_ATTRIBUTES:return jobAttrs(req,base);
            case Ipp.CANCEL_JOB:return cancelJob(req);
            case Ipp.GET_JOBS:return getJobs(req);
            default:return new Ipp.Reply(req,Ipp.OPERATION_NOT_SUPPORTED).finish();
        }
    }
    private byte[] printerAttrs(Ipp.Request req,String uri)throws IOException {
        Ipp.Reply w=new Ipp.Reply(req,Ipp.OK).group(Ipp.PRINTER);
        w.string(Ipp.URI,"printer-uri-supported",uri);
        w.string(Ipp.KEYWORD,"uri-authentication-supported","none");
        w.string(Ipp.KEYWORD,"uri-security-supported","none");
        w.string(Ipp.NAME,"printer-name","SUNMI V2 Receipt");
        w.string(Ipp.TEXT,"printer-info","58 mm roll / 48 mm print area / 203 dpi thermal");
        w.string(Ipp.TEXT,"printer-make-and-model","SUNMI V2 Thermal 58mm");
        w.string(Ipp.KEYWORD,"printer-location","LAN");
        w.string(Ipp.KEYWORD,"ipp-versions-supported","1.1");
        w.integer(Ipp.ENUM,"operations-supported",Ipp.PRINT_JOB);
        for(int op:new int[]{Ipp.VALIDATE_JOB,Ipp.CANCEL_JOB,Ipp.GET_JOB_ATTRIBUTES,
                Ipp.GET_JOBS,Ipp.GET_PRINTER_ATTRIBUTES})w.integer(Ipp.ENUM,"operations-supported",op);
        w.bool("printer-is-accepting-jobs",driver.isReady());
        w.integer(Ipp.ENUM,"printer-state",driver.isReady()?3:5);
        w.string(Ipp.KEYWORD,"printer-state-reasons",driver.isReady()?"none":"paused");
        w.string(Ipp.TEXT,"printer-state-message",driver.isReady()?"Ready":"Printer service disconnected");
        w.integer(Ipp.INTEGER,"queued-job-count",queuedJobs());
        w.bool("color-supported",false);
        w.bool("multiple-document-jobs-supported",false);
        w.bool("ipp-attribute-fidelity-supported",false);
        w.string(Ipp.KEYWORD,"compression-supported","none");
        w.string(Ipp.MIME,"document-format-default","application/pdf");
        for(String mime:new String[]{"application/pdf","image/png","image/jpeg"})
            w.string(Ipp.MIME,"document-format-supported",mime);
        w.string(Ipp.KEYWORD,"print-color-mode-default","monochrome");
        w.string(Ipp.KEYWORD,"print-color-mode-supported","monochrome");
        w.string(Ipp.KEYWORD,"sides-default","one-sided");
        w.string(Ipp.KEYWORD,"sides-supported","one-sided");
        w.string(Ipp.KEYWORD,"media-type-supported","stationery");
        w.string(Ipp.KEYWORD,"media-source-supported","main");
        w.string(Ipp.KEYWORD,"media-default",MEDIA[3]);
        w.string(Ipp.KEYWORD,"media-ready",MEDIA[3]);
        for(String media:MEDIA)w.string(Ipp.KEYWORD,"media-supported",media);
        w.mediaCollection("media-col-default",MEDIA[3],LENGTHS[3]);
        w.mediaCollection("media-col-ready",MEDIA[3],LENGTHS[3]);
        for(int i=0;i<MEDIA.length;i++)w.mediaCollection("media-col-database",MEDIA[i],LENGTHS[i]);
        w.integer(Ipp.INTEGER,"media-left-margin-supported",500);
        w.integer(Ipp.INTEGER,"media-right-margin-supported",500);
        w.integer(Ipp.INTEGER,"media-top-margin-supported",0);
        w.integer(Ipp.INTEGER,"media-bottom-margin-supported",0);
        w.resolution("printer-resolution-default",203);
        w.resolution("printer-resolution-supported",203);
        w.integer(Ipp.ENUM,"print-quality-default",4);
        w.integer(Ipp.ENUM,"print-quality-supported",4);
        w.range("copies-supported",1,9);
        w.integer(Ipp.INTEGER,"copies-default",1);
        w.range("job-k-octets-supported",0,30720);
        w.integer(Ipp.INTEGER,"job-priority-default",50);
        w.string(Ipp.KEYWORD,"job-creation-attributes-supported","media");
        for(String s:new String[]{"media-col","copies","job-name","print-color-mode","sides"})
            w.string(Ipp.KEYWORD,"job-creation-attributes-supported",s);
        return w.finish();
    }
    private int queuedJobs() {
        int n=0;for(Job j:jobs.values())if(j.state==3||j.state==5)n++;return n;
    }
    private byte[] acceptJob(Ipp.Request req,InputStream document,String base)throws IOException {
        String format=req.string("document-format","application/octet-stream");
        if(!format.equals("application/pdf")&&!format.equals("image/jpeg")
                &&!format.equals("image/png")&&!format.equals("application/octet-stream"))
            return new Ipp.Reply(req,Ipp.FORMAT_NOT_SUPPORTED).finish();
        if(!driver.isReady())return new Ipp.Reply(req,Ipp.INTERNAL_ERROR).finish();
        int id=nextJob.getAndIncrement();
        File file=new File(spool,"job-"+id+".bin");
        long size=0;
        try(FileOutputStream f=new FileOutputStream(file)){
            byte[] block=new byte[8192];int n;
            while((n=document.read(block))!=-1){size+=n;if(size>MAX_BODY)throw new IOException("Job too large");f.write(block,0,n);}
        }catch(IOException e){file.delete();throw e;}
        if(size==0){file.delete();return new Ipp.Reply(req,Ipp.FORMAT_NOT_SUPPORTED).finish();}
        if(format.equals("application/octet-stream"))format=PrintRenderer.detect(file);
        if(format==null){file.delete();return new Ipp.Reply(req,Ipp.FORMAT_NOT_SUPPORTED).finish();}
        String name=req.string("job-name","Receipt");
        Job job=new Job(id,name,format,file,base.substring(0,base.indexOf("/ipp/print"))+"/ipp/print?job="+id);
        jobs.put(id,job);
        int copies=Math.max(1,Math.min(9,req.number("copies",1)));
        printQueue.execute(()->print(job,copies));
        return describeJob(new Ipp.Reply(req,Ipp.OK).group(Ipp.JOB),job).finish();
    }
    private void print(Job job,int copies){
        job.state=5;
        try {
            for(int i=0;i<copies;i++) {
                if(job.canceled)break;
                PrintRenderer.print(job.spoolFile,job.format,driver,()->job.canceled);
            }
            job.state=job.canceled?7:9;
            job.reason=job.canceled?"job-canceled-by-user":"none";
        } catch(Exception e) {
            job.state=8;job.reason="job-printing-error";
            Log.e("SunmiIPP","Job "+job.id+" failed",e);
        } finally {job.spoolFile.delete();}
    }
    private Ipp.Reply describeJob(Ipp.Reply w,Job job)throws IOException {
        w.integer(Ipp.INTEGER,"job-id",job.id);
        w.string(Ipp.URI,"job-uri",job.uri);
        w.string(Ipp.NAME,"job-name",job.name);
        w.integer(Ipp.ENUM,"job-state",job.state);
        w.string(Ipp.KEYWORD,"job-state-reasons",job.reason);
        return w;
    }
    private Job requestedJob(Ipp.Request req){
        int id=req.number("job-id",-1);
        if(id<0){
            String uri=req.string("job-uri","");
            int i=uri.indexOf("job=");
            if(i>=0)try{id=Integer.parseInt(uri.substring(i+4));}catch(NumberFormatException ignored){}
        }
        return jobs.get(id);
    }
    private byte[] jobAttrs(Ipp.Request req,String base)throws IOException {
        Job j=requestedJob(req);
        if(j==null)return new Ipp.Reply(req,Ipp.NOT_FOUND).finish();
        return describeJob(new Ipp.Reply(req,Ipp.OK).group(Ipp.JOB),j).finish();
    }
    private byte[] cancelJob(Ipp.Request req)throws IOException {
        Job j=requestedJob(req);
        if(j==null)return new Ipp.Reply(req,Ipp.NOT_FOUND).finish();
        j.canceled=true;
        return new Ipp.Reply(req,Ipp.OK).finish();
    }
    private byte[] getJobs(Ipp.Request req)throws IOException {
        Ipp.Reply w=new Ipp.Reply(req,Ipp.OK);
        List<Job> snapshot=new ArrayList<>(jobs.values());
        snapshot.sort((a,b)->Integer.compare(b.id,a.id));
        for(int i=0;i<Math.min(20,snapshot.size());i++)
            describeJob(w.group(Ipp.JOB),snapshot.get(i));
        return w.finish();
    }
    @Override public synchronized void close(){
        alive.set(false);
        try{if(socket!=null)socket.close();}catch(IOException ignored){}
        connections.shutdownNow();printQueue.shutdownNow();
    }
}
