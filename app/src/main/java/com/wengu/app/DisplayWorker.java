package com.wengu.app;

import android.content.Context;
import androidx.work.*;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class DisplayWorker extends Worker {
    public DisplayWorker(Context c,WorkerParameters p){super(c,p);}
    public Result doWork() {
        try {
            Store store=new Store(getApplicationContext());synchronized(Store.LOCK){JSONObject checked=store.load();Store.put(Store.session(checked),"last_worker",System.currentTimeMillis());store.save(checked);}
            new DeliveryEngine(getApplicationContext()).apply(null,true,System.currentTimeMillis());
            JSONObject state=new Store(getApplicationContext()).load();
            return Store.settings(state).optBoolean("enabled")&&Store.session(state).optJSONObject("pending")!=null?Result.retry():Result.success();
        }catch(Exception e){try{Store store=new Store(getApplicationContext());synchronized(Store.LOCK){JSONObject state=store.load();Store.put(Store.session(state),"status","自动展示暂未完成："+(e.getMessage()==null?"请打开应用重试":e.getMessage()));store.save(state);}}catch(Exception ignored){}return getRunAttemptCount()<2?Result.retry():Result.failure();}
    }
    public static void schedule(Context c) {
        try {
            JSONObject state=new Store(c).load();WorkManager manager=WorkManager.getInstance(c);
            if(!Store.settings(state).optBoolean("enabled")){manager.cancelUniqueWork("wengu-display");manager.cancelUniqueWork("wengu-retry");return;}
            long now=System.currentTimeMillis();long next=ReviewPlanner.nextAttempt(state,now,ZoneId.systemDefault());
            PeriodicWorkRequest request=new PeriodicWorkRequest.Builder(DisplayWorker.class,1,TimeUnit.HOURS)
                    .setInitialDelay(Math.max(60000,next-now),TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build();
            manager.enqueueUniquePeriodicWork("wengu-display",ExistingPeriodicWorkPolicy.KEEP,request);
        }catch(Exception e) { recordScheduleFailure(c,e); }
    }
    public static void retrySoon(Context c) {
        try {
            JSONObject state=new Store(c).load();long now=System.currentTimeMillis(),when=now+60000;ZoneId zone=ZoneId.systemDefault();
            if(!ReviewPlanner.active(state,when,zone)){java.time.ZonedDateTime date=java.time.Instant.ofEpochMilli(when).atZone(zone);java.time.ZonedDateTime start=date.toLocalDate().atTime(Store.settings(state).optInt("start_hour",8),0).atZone(zone);if(start.toInstant().toEpochMilli()<=when)start=start.plusDays(1);when=start.toInstant().toEpochMilli();}
            OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(DisplayWorker.class).setInitialDelay(Math.max(60000,when-now),TimeUnit.MILLISECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,1,TimeUnit.MINUTES).build();
            WorkManager.getInstance(c).enqueueUniqueWork("wengu-retry",ExistingWorkPolicy.KEEP,request);
        }catch(Exception ignored){}
    }
    public static void reschedule(Context c){WorkManager.getInstance(c).cancelUniqueWork("wengu-display");schedule(c);}
    private static void recordScheduleFailure(Context c,Exception failure){try{Store store=new Store(c);synchronized(Store.LOCK){JSONObject state=store.load();Store.put(Store.session(state),"status","自动展示任务未排入，请打开运行状态重试");Store.log(state,"schedule_failed","",failure.getMessage()==null?"请重试":failure.getMessage());store.save(state);}}catch(Exception ignored){}}
}
