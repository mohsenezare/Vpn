package com.aegis.vpn;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.widget.Toast;

/** Notification drawer shortcut and requestable Quick Settings tile, not a VPN engine. */
final class AegisShortcuts {
    static final String ACTION_TOGGLE="com.aegis.vpn.action.TOGGLE";
    private static final String CHANNEL="aegis-vpn-shortcuts";
    private static final int NOTIFICATION_ID=92, ASK_NOTIFICATIONS=8393;
    private static volatile boolean connected=false, connecting=false;
    private AegisShortcuts(){}

    static boolean connected(){return connected||SingVpnService.state==SingVpnService.TUNNEL_ACTIVE;}
    static boolean connecting(){return connecting||SingVpnService.state==SingVpnService.STARTING;}

    static void requestTileUpdate(Context context){
        try{
            TileService.requestListeningState(context,new ComponentName(context,AegisQuickTileService.class));
        }catch(Exception ignored){ /* Tile might not be installed yet. */ }
    }

    static void publish(Context context,boolean isConnected,boolean isConnecting){
        connected=isConnected;connecting=isConnecting;
        requestTileUpdate(context);
        NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
        if(manager==null)return;
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"Aegis VPN quick access",NotificationManager.IMPORTANCE_LOW));
        // Native sing-box owns its mandatory foreground notification while running.
        if(SingVpnService.state==SingVpnService.TUNNEL_ACTIVE||
           SingVpnService.state==SingVpnService.STARTING){
            manager.cancel(NOTIFICATION_ID);return;
        }
        if(Build.VERSION.SDK_INT>=33 &&
           context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            manager.cancel(NOTIFICATION_ID);return;
        }
        if(!manager.areNotificationsEnabled())return;
        Intent open=new Intent(context,MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(context,193,open,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent toggle=new Intent(context,MainActivity.class).setAction(ACTION_TOGGLE);
        toggle.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent action=PendingIntent.getActivity(context,194,toggle,
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String text=isConnected?"Connected via OpenVPN":
            isConnecting?"Connecting…":"Disconnected · tap to connect";
        String verb=isConnected?"Disconnect":"Connect";
        Notification notice=new Notification.Builder(context,CHANNEL)
            .setSmallIcon(R.drawable.ic_aegis_status)
            .setContentTitle("Aegis VPN")
            .setContentText(text)
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            .setOngoing(isConnected||isConnecting)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_play,verb,action)
            .build();
        manager.notify(NOTIFICATION_ID,notice);
    }

    static void nativeStatusChanged(Context context,int state){
        publish(context,state==SingVpnService.TUNNEL_ACTIVE,state==SingVpnService.STARTING);
    }

    static void maybeAskNotificationPermission(Activity activity){
        if(Build.VERSION.SDK_INT<33)return;
        android.content.SharedPreferences pref=activity.getSharedPreferences("aegis-shortcuts",Context.MODE_PRIVATE);
        if(activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)return;
        if(pref.getBoolean("asked-notifications",false))return;
        pref.edit().putBoolean("asked-notifications",true).apply();
        activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},ASK_NOTIFICATIONS);
    }

    static void enableNotifications(Activity activity){
        if(Build.VERSION.SDK_INT>=33 &&
           activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},ASK_NOTIFICATIONS);
            return;
        }
        Toast.makeText(activity,"Aegis VPN notification is enabled",Toast.LENGTH_SHORT).show();
    }

    static void addQuickTile(Activity activity){
        if(Build.VERSION.SDK_INT>=33){
            android.app.StatusBarManager bar=
                (android.app.StatusBarManager)activity.getSystemService(Context.STATUS_BAR_SERVICE);
            if(bar!=null){
                try{
                    bar.requestAddTileService(
                        new ComponentName(activity,AegisQuickTileService.class),
                        "Aegis VPN",
                        Icon.createWithResource(activity,R.drawable.ic_aegis_status),
                        activity.getMainExecutor(),
                        result->{
                            if(result==android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
                                Toast.makeText(activity,"Aegis VPN added to Quick Settings",Toast.LENGTH_LONG).show();
                            else
                                Toast.makeText(activity,"Open Quick Settings → Edit → Add card → Aegis VPN",Toast.LENGTH_LONG).show();
                        });
                    return;
                }catch(Exception ignored){ /* Honor/other launchers may not support prompt. */ }
            }
        }
        new GlassDialog.Builder(activity)
            .setTitle("Add to Quick Settings")
            .setMessage("Pull down Quick Settings twice → Edit → Add card → Aegis VPN. Confirm the new tile.")
            .setPositiveButton("OK",null).show();
    }
}
