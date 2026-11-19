package dev.sunmibridge;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public final class NetworkUtils {
    private NetworkUtils(){}
    public static String localIp(){
        try {
            List<NetworkInterface> interfaces=Collections.list(NetworkInterface.getNetworkInterfaces());
            // Prefer the Wi-Fi interface: V2s can have a cellular private IP too.
            for(int pass=0;pass<2;pass++){
                for(NetworkInterface nif:interfaces){
                    if(!nif.isUp()||nif.isLoopback()||nif.isVirtual())continue;
                    String name=nif.getName().toLowerCase(java.util.Locale.US);
                    boolean wifi=name.startsWith("wlan")||name.startsWith("wifi")||name.startsWith("ap");
                    if(pass==0&&!wifi)continue;
                    if(pass==1&&wifi)continue;
                    for(InetAddress address:Collections.list(nif.getInetAddresses())){
                        if(address instanceof Inet4Address && !address.isLoopbackAddress()
                                && address.isSiteLocalAddress())return address.getHostAddress();
                    }
                }
            }
        }catch(Exception ignored){}
        return "127.0.0.1";
    }
}
