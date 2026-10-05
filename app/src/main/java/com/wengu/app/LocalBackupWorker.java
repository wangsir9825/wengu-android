package com.wengu.app;

import android.content.Context;
import androidx.work.*;

public final class LocalBackupWorker extends Worker {
    public LocalBackupWorker(Context c,WorkerParameters p){super(c,p);}
    @Override public Result doWork(){
        if(!LocalBackup.enabled(getApplicationContext())||isStopped())return Result.success();
        try{LocalBackup.write(getApplicationContext());return Result.success();}
        catch(Exception e){
            // A revoked SAF grant or a pending restore requires user action; transient I/O can retry.
            if(LocalBackup.retryable(e)&&getRunAttemptCount()<2&&!isStopped())return Result.retry();
            return Result.failure(new Data.Builder().putString("error",LocalBackup.failureMessage(e)).build());
        }
    }
}
