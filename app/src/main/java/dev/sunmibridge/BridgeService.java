package dev.sunmibridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public final class BridgeService extends Service {
    public static final String START="dev.sunmibridge.START", STOP="dev.sunmibridge.STOP", TEST="dev.sunmibridge.TEST";
    private static final int NOTIFICATION_ID=1384;
    private IppServer server;
    private SunmiDriver driver;
    private NsdManager nsd;
    private NsdManager.RegistrationListener registration;
    private WifiManager.MulticastLock multicastLock;

    @Override public void onCreate(){
        super.onCreate();
        driver=new SunmiDriver(this);driver.bind();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent!=null?intent.getAction():START;
        if(STOP.equals(action)){
            shutdown();stopSelf();return START_NOT_STICKY;
        }
        if(TEST.equals(action)){
            new Thread(()->{try{driver.test();}catch(Exception e){Log.e("SunmiIPP","test print",e);}},"test-print").start();
            return START_STICKY;
        }
        startForeground(NOTIFICATION_ID,notification());
        try{
            if(server==null){
                server=new IppServer(driver,getCacheDir());
                server.start();
                registerBonjour();
            }
            getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("running",true).apply();
        }catch(Exception e){
            Log.e("SunmiIPP","Cannot start server",e);
            shutdown();stopSelf();return START_NOT_STICKY;
        }
        return START_STICKY;
    }
    private Notification notification(){
        String channel="sunmi_network_printer";
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(channel,"Network printer",NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,channel):new Notification.Builder(this);
        return b.setContentTitle("SUNMI V2 network printer")
                .setContentText("IPP on "+NetworkUtils.localIp()+":"+IppServer.PORT)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentIntent(pi).setOngoing(true).build();
    }
    private void registerBonjour(){
        try {
            WifiManager wifi=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(wifi!=null){
                multicastLock=wifi.createMulticastLock("SunmiIPP");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
            nsd=(NsdManager)getSystemService(Context.NSD_SERVICE);
            NsdServiceInfo info=new NsdServiceInfo();
            info.setServiceName("SUNMI V2 Receipt");
            info.setServiceType("_ipp._tcp.");
            info.setPort(IppServer.PORT);
            info.setAttribute("txtvers","1");
            info.setAttribute("qtotal","1");
            info.setAttribute("rp","ipp/print");
            info.setAttribute("ty","SUNMI V2 58mm Thermal");
            info.setAttribute("note","58mm receipt roll; 48mm imageable area");
            info.setAttribute("product","(SUNMI V2 Thermal)");
            info.setAttribute("pdl","application/pdf,image/png,image/jpeg");
            info.setAttribute("Color","F");
            info.setAttribute("Duplex","F");
            registration=new NsdManager.RegistrationListener(){
                @Override public void onServiceRegistered(NsdServiceInfo x){Log.i("SunmiIPP","mDNS published "+x.getServiceName());}
                @Override public void onRegistrationFailed(NsdServiceInfo x,int e){Log.w("SunmiIPP","mDNS registration failed "+e);}
                @Override public void onServiceUnregistered(NsdServiceInfo x){}
                @Override public void onUnregistrationFailed(NsdServiceInfo x,int e){}
            };
            nsd.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration);
        }catch(Exception e){Log.w("SunmiIPP","mDNS unavailable: manual IPP URL still works",e);}
    }
    private void shutdown(){
        if(server!=null){server.close();server=null;}
        if(nsd!=null&&registration!=null){try{nsd.unregisterService(registration);}catch(Exception ignored){}}
        registration=null;
        if(multicastLock!=null&&multicastLock.isHeld())multicastLock.release();
        multicastLock=null;
        getSharedPreferences("settings",MODE_PRIVATE).edit().putBoolean("running",false).apply();
        stopForeground(true);
    }
    @Override public void onDestroy(){shutdown();if(driver!=null)driver.unbind();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
