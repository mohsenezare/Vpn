package com.aegis.vpn;

import java.util.Locale;

/**
 * Live device-wide network throughput from cumulative Android TrafficStats counters.
 * This is not an isolated VPN-byte meter: background apps and tunnel overhead can
 * contribute. No synthetic speeds or simulated traffic are generated.
 */
final class TrafficMeter {
    private boolean valid=true, sampling=false;
    private long previousRx, previousTx, previousAt;
    private String down="0.00 Mbps", up="0.00 Mbps";
    String download(){return valid?down:"— Mbps";}
    String upload(){return valid?up:"— Mbps";}

    void sample(long nowMillis,long totalRxBytes,long totalTxBytes,boolean tunnelActive){
        if(totalRxBytes<0||totalTxBytes<0){
            valid=false;sampling=false;down=up="— Mbps";return;
        }
        valid=true;
        if(!tunnelActive){
            sampling=false;down=up="0.00 Mbps";return;
        }
        if(!sampling||nowMillis<=previousAt||totalRxBytes<previousRx||totalTxBytes<previousTx){
            sampling=true;previousAt=nowMillis;previousRx=totalRxBytes;previousTx=totalTxBytes;
            down=up="0.00 Mbps";return;
        }
        long elapsed=nowMillis-previousAt;
        if(elapsed<250)return;
        double downstream=(totalRxBytes-previousRx)*8.0/(elapsed*1000.0);
        double upstream=(totalTxBytes-previousTx)*8.0/(elapsed*1000.0);
        previousAt=nowMillis;previousRx=totalRxBytes;previousTx=totalTxBytes;
        down=String.format(Locale.US,"%.2f Mbps",downstream);
        up=String.format(Locale.US,"%.2f Mbps",upstream);
    }
}
