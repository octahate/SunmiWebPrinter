package dev.sunmibridge;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private TextView status;
    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        ScrollView scroll=new ScrollView(this);
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int px=(int)(getResources().getDisplayMetrics().density*20);
        layout.setPadding(px,px,px,px);
        scroll.addView(layout);
        TextView title=new TextView(this);
        title.setText("SUNMI V2 Network Printer");
        title.setTextSize(23);title.setTypeface(null,Typeface.BOLD);
        layout.addView(title);
        status=new TextView(this);status.setTextSize(15);status.setPadding(0,px,0,px);
        layout.addView(status);
        addButton(layout,"Start network printer",v->command(BridgeService.START));
        addButton(layout,"Stop network printer",v->command(BridgeService.STOP));
        addButton(layout,"Print test receipt",v->command(BridgeService.TEST));
        addButton(layout,"Refresh address",v->refresh());
        TextView help=new TextView(this);
        help.setText("\nPAPER\n58 mm thermal roll; 384 dots across (~48 mm printable).\n"
                +"Supported advertised heights: 50, 100, 150, 200, 300 mm.\n"
                +"PDF and PNG/JPEG images are rasterized at print width with monochrome dithering.\n\n"
                +"ADD ON YOUR COMPUTER\n"
                +"Use the IPP URL shown above; choose IPP / Generic IPP, not a 58mm ESC/POS driver.\n"
                +"The printer also broadcasts Bonjour on Wi-Fi.\n\n"
                +"SECURITY\nAnyone on the same LAN who can reach the address can send a job. "
                +"Use only on a trusted Wi-Fi network.\n\n"
                +"NOTE\nThe original V2 runs Android 7.1. iOS AirPrint automatic discovery "
                +"is not guaranteed on this Android version; manual IPP printing from macOS, Windows, and CUPS "
                +"is the first implementation target.");
        help.setTextSize(14);layout.addView(help);
        setContentView(scroll);
        refresh();
    }
    private void addButton(LinearLayout layout,String text,View.OnClickListener listener){
        Button b=new Button(this);b.setText(text);b.setOnClickListener(listener);layout.addView(b);
    }
    private void command(String action){
        Intent i=new Intent(this,BridgeService.class);i.setAction(action);
        if(Build.VERSION.SDK_INT>=26 && !BridgeService.STOP.equals(action))startForegroundService(i);
        else startService(i);
        refresh();
    }
    @Override protected void onResume(){super.onResume();refresh();}
    private void refresh(){
        boolean active=getSharedPreferences("settings",MODE_PRIVATE).getBoolean("running",false);
        status.setText("Status: "+(active?"Started":"Stopped")+"\n"
                +"IPP: ipp://"+NetworkUtils.localIp()+":"+IppServer.PORT+"/ipp/print\n"
                +"Diagnostic: http://"+NetworkUtils.localIp()+":"+IppServer.PORT+"/health");
    }
}
