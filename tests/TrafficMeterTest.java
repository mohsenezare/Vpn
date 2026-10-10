package com.aegis.vpn;
public final class TrafficMeterTest {
    private static void ok(boolean pass,String caseName){if(!pass)throw new AssertionError(caseName);}
    public static void main(String[] args){
        TrafficMeter meter=new TrafficMeter();
        meter.sample(1000,10,20,false);
        ok(meter.download().equals("0.00 Mbps"),"idle shows zero");
        meter.sample(2000,1000,1000,true);
        ok(meter.download().equals("0.00 Mbps"),"first sample is baseline");
        meter.sample(3000,1001000,501000,true);
        ok(meter.download().equals("8.00 Mbps"),"real download rate in Mbps");
        ok(meter.upload().equals("4.00 Mbps"),"real upload rate in Mbps");
        meter.sample(4000,1002000,501200,false);
        ok(meter.download().equals("0.00 Mbps"),"disconnect resets speed");
        meter.sample(5000,123,456,true);
        meter.sample(6000,-1,-1,true);
        ok(meter.download().equals("— Mbps"),"unsupported Android counters are not faked");
        meter.sample(7000,1000,1000,true);
        ok(meter.download().equals("0.00 Mbps"),"counter recovery resets baseline");
        System.out.println("7 device traffic rate checks passed");
    }
}
