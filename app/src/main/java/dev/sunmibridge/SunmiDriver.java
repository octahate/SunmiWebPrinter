package dev.sunmibridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.RemoteException;
import android.util.Log;

import com.sunmi.peripheral.printer.InnerPrinterCallback;
import com.sunmi.peripheral.printer.InnerPrinterManager;
import com.sunmi.peripheral.printer.InnerResultCallback;
import com.sunmi.peripheral.printer.SunmiPrinterService;

/** Binds SUNMI's official service, avoiding hand-copied AIDL signature mismatches. */
public final class SunmiDriver {
    private final Context context;
    private volatile SunmiPrinterService service;
    private InnerPrinterCallback binding;
    private boolean bound;

    public SunmiDriver(Context context){this.context=context.getApplicationContext();}
    public synchronized void bind(){
        if(binding!=null)return;
        binding=new InnerPrinterCallback(){
            @Override protected void onConnected(SunmiPrinterService printer){service=printer;Log.i("SunmiIPP","printer connected");}
            @Override protected void onDisconnected(){service=null;Log.w("SunmiIPP","printer disconnected");}
        };
        try{bound=InnerPrinterManager.getInstance().bindService(context,binding);}
        catch(Exception e){Log.e("SunmiIPP","Cannot bind printer",e);}
    }
    public boolean isReady(){return service!=null;}
    public void awaitReady()throws Exception {
        for(int i=0;i<60;i++) {
            if(service!=null)return;
            Thread.sleep(100);
        }
        throw new IllegalStateException("SUNMI printer service unavailable");
    }
    private InnerResultCallback callback(){
        return new InnerResultCallback(){
            @Override public void onRunResult(boolean ok) {if(!ok)Log.w("SunmiIPP","Printer command failed");}
            @Override public void onReturnString(String result) {}
            @Override public void onRaiseException(int code,String message) {Log.e("SunmiIPP","Printer error "+code+": "+message);}
            @Override public void onPrintResult(int code,String message) {if(code!=0)Log.w("SunmiIPP","Print result "+code+": "+message);}
        };
    }
    public void printStrip(Bitmap mono) throws Exception {
        if(mono.getWidth()!=384 || mono.getHeight()>256 || mono.getConfig()!=Bitmap.Config.ARGB_8888)
            throw new IllegalArgumentException("Printer strips must be 384px wide, <=256px tall, ARGB_8888");
        awaitReady();
        SunmiPrinterService current=service;
        if(current==null)throw new IllegalStateException("Printer disconnected");
        int status=current.updatePrinterState();
        if(status==4)throw new IOExceptionLike("Out of paper");
        if(status==5)throw new IOExceptionLike("Print head overheated");
        if(status!=1 && status!=2)Log.w("SunmiIPP","Printer state "+status);
        current.printBitmap(mono,callback());
        Thread.sleep(45); // Avoid overwhelming older V2 print-service buffers.
    }
    public void finishJob()throws Exception {
        awaitReady();
        service.lineWrap(3,callback());
    }
    public void test()throws Exception {
        awaitReady();
        service.printText("SUNMI V2 NETWORK PRINTER\n58 mm roll / 384 dots\nIPP port 8631\n\n",callback());
        service.lineWrap(3,callback());
    }
    public synchronized void unbind(){
        if(binding!=null && bound){
            try{InnerPrinterManager.getInstance().unBindService(context,binding);}catch(Exception ignored){}
        }
        service=null;binding=null;bound=false;
    }
    private static class IOExceptionLike extends Exception{IOExceptionLike(String msg){super(msg);}}
}
