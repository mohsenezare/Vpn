package com.aegis.vpn;
import android.app.job.*;
import android.content.*;
import android.os.*;
/** Refresh in the background when Android schedules it; preserve cache on failure. */
public final class RefreshJob extends JobService {
    private static final int JOB_ID=738290;
    public static void schedule(Context context){
        JobScheduler scheduler=(JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if(scheduler==null)return;
        JobInfo spec=new JobInfo.Builder(JOB_ID,new ComponentName(context,RefreshJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(21600000L).build();
        scheduler.schedule(spec);
    }
    @Override public boolean onStartJob(JobParameters p){
        new Thread(()->{
            boolean failed=false;
            try{FreeDirectory d=new FreeDirectory(getApplicationContext());if(d.isStale())d.fetch();}
            catch(Exception e){failed=true;}
            final boolean retry=failed;
            new Handler(Looper.getMainLooper()).post(()->jobFinished(p,retry));
        },"directory-refresh").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters p){return true;}
}
