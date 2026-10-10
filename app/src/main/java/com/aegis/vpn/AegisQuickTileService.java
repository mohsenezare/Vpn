package com.aegis.vpn;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** System Quick Settings card; pressing it uses the already-selected VPN mode. */
public final class AegisQuickTileService extends TileService {
    @Override public void onStartListening(){
        super.onStartListening();
        Tile tile=getQsTile();
        if(tile==null)return;
        boolean on=AegisShortcuts.connected();
        boolean starting=AegisShortcuts.connecting();
        tile.setLabel("Aegis VPN");
        tile.setIcon(Icon.createWithResource(this,R.drawable.ic_aegis_status));
        tile.setState(on?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);
        if(Build.VERSION.SDK_INT>=29)tile.setSubtitle(on?"Connected":starting?"Connecting":"Tap to connect");
        tile.updateTile();
    }
    @Override public void onClick(){
        super.onClick();
        Runnable launch=()->{
            Intent command=new Intent(this,MainActivity.class).setAction(AegisShortcuts.ACTION_TOGGLE);
            command.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if(Build.VERSION.SDK_INT>=34){
                PendingIntent pending=PendingIntent.getActivity(this,195,command,
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pending);
            }else{
                startActivityAndCollapse(command);
            }
        };
        if(isLocked())unlockAndRun(launch);else launch.run();
    }
}
