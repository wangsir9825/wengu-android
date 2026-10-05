package com.wengu.app;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.UUID;

/** One process-wide lock coordinates UI edits, backup operations and background deliveries. */
public final class Store {
    public static final Object LOCK = new Object();
    private static final String BACKUP_EPOCH="_backup_epoch",BACKUP_REVISION="_backup_revision",BACKUP_FINGERPRINT="_backup_fingerprint";
    private final Context context;
    public Store(Context context) { this.context = context.getApplicationContext(); }
    public static JSONObject object() { return new JSONObject(); }
    public static JSONObject put(JSONObject o, String key, Object value) {
        try { o.put(key, value); return o; } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public static JSONObject copy(JSONObject o) {
        try { return new JSONObject(o.toString()); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
    public static JSONObject settings(JSONObject state) { return state.optJSONObject("settings"); }
    public static JSONObject session(JSONObject state) { return state.optJSONObject("session"); }
    public static JSONArray items(JSONObject state) { return state.optJSONArray("items"); }
    public static JSONObject fresh() {
        JSONObject settings = object();
        put(settings,"target",2); put(settings,"enabled",false); put(settings,"budget",4);
        put(settings,"new_limit",1); put(settings,"start_hour",8); put(settings,"end_hour",22);
        put(settings,"font",28); put(settings,"lock_y",56); put(settings,"home_y",50);
        put(settings,"scrim",25); put(settings,"theme",6); put(settings,"random",false);put(settings,"ui_style","minimal");
        put(settings,"dark_text",false); put(settings,"photo",""); put(settings,"serif",false);
        JSONObject session = object(); put(session,"status","写下第一条经验，开始温故");
        JSONObject state = object(); put(state,"schema_version",1); put(state,"settings",settings);
        put(state,"session",session); put(state,"items",new JSONArray()); put(state,"events",new JSONArray());
        return state;
    }
    public JSONObject load() throws IOException {
        synchronized (LOCK) {
            AtomicFile f = new AtomicFile(new File(context.getFilesDir(),"experiences.json"));
            boolean present=f.getBaseFile().exists()||new File(f.getBaseFile()+".bak").exists();
            try {
                JSONObject state=present?new JSONObject(new String(f.readFully(),StandardCharsets.UTF_8)):fresh();
                prepareBackupMetadata(state,present);publishBackupMetadata(state,present);return state;
            }catch (Exception e) {
                LocalBackup.preferences(context).edit().putBoolean("store_known",false).apply();
                throw new IOException("经验数据读取失败，请保留数据并通过备份恢复",e);
            }
        }
    }
    public void save(JSONObject state) throws IOException {
        synchronized (LOCK) {
            AtomicFile f = new AtomicFile(new File(context.getFilesDir(),"experiences.json"));
            FileOutputStream out = null;
            try {
                JSONObject previous=load();String fingerprint=backupFingerprint(state);long revision=revision(previous);String epoch=epoch(previous);
                // Only the payload exported by Backup changes coverage. Session/events stay local.
                if(!fingerprint.equals(previous.optString(BACKUP_FINGERPRINT))){
                    if(revision==Long.MAX_VALUE){epoch=UUID.randomUUID().toString();revision=0;}else revision++;
                }
                put(state,BACKUP_EPOCH,epoch);put(state,BACKUP_REVISION,revision);put(state,BACKUP_FINGERPRINT,fingerprint);
                out=f.startWrite();out.write(state.toString().getBytes(StandardCharsets.UTF_8));f.finishWrite(out);out=null;
                publishBackupMetadata(state,true);LocalBackup.request(context);
            }
            catch (Exception e) { if (out!=null) f.failWrite(out); throw new IOException("保存失败，请重试",e); }
        }
    }
    public static long revision(JSONObject state){return Math.max(0,state.optLong(BACKUP_REVISION,0));}
    public static String epoch(JSONObject state){return state.optString(BACKUP_EPOCH);}
    private void prepareBackupMetadata(JSONObject state,boolean present)throws Exception {
        android.content.SharedPreferences p=LocalBackup.preferences(context);String epoch=epoch(state);long revision=revision(state);
        if(epoch.isEmpty()){
            // Old versions have no acknowledged revision. Reuse an unconfirmed migration epoch,
            // but never reuse the epoch of a data file that has subsequently disappeared.
            String cached=p.getString("store_epoch","");
            if(!cached.isEmpty()&&p.getBoolean("store_file_present",false)==present){epoch=cached;revision=Math.max(0,p.getLong("store_revision",0));}
            else {epoch=UUID.randomUUID().toString();revision=0;}
        }
        put(state,BACKUP_EPOCH,epoch);put(state,BACKUP_REVISION,revision);put(state,BACKUP_FINGERPRINT,backupFingerprint(state));
    }
    private void publishBackupMetadata(JSONObject state,boolean present){
        int privateCount=0;JSONArray items=items(state);for(int i=0;i<items.length();i++){JSONObject item=items.optJSONObject(i);if(item!=null&&item.optBoolean("private"))privateCount++;}
        // No LocalBackup.LOCK here: backup writing takes that lock before Store.LOCK.
        // SharedPreferences.apply publishes one atomic in-memory snapshot for main-thread status.
        LocalBackup.preferences(context).edit().putBoolean("store_known",true).putBoolean("store_file_present",present)
                .putString("store_epoch",epoch(state)).putLong("store_revision",revision(state))
                .putInt("store_count",items.length()).putInt("store_private_count",privateCount).apply();
    }
    private static String backupFingerprint(JSONObject state)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");digest.update(items(state).toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte)0);digest.update(settings(state).toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder();for(byte value:digest.digest()){int n=value&255;result.append(Character.forDigit(n>>>4,16)).append(Character.forDigit(n&15,16));}return result.toString();
    }
    public static JSONObject find(JSONObject state, String id) {
        JSONArray arr=items(state);
        for(int i=0;i<arr.length();i++) { JSONObject item=arr.optJSONObject(i); if(item!=null && id.equals(item.optString("id"))) return item; }
        return null;
    }
    public static JSONObject newExperience() {
        JSONObject o=object(); put(o,"id",UUID.randomUUID().toString()); put(o,"summary","");
        put(o,"body",""); put(o,"scenario",""); put(o,"exception",""); put(o,"tags",new JSONArray());
        put(o,"important",false); put(o,"private",false); put(o,"status","active"); put(o,"photo","");
        put(o,"theme",-1); put(o,"stage",-1); put(o,"due",LocalDate.now().toEpochDay());
        put(o,"last_success",0L); put(o,"modified",System.currentTimeMillis()); put(o,"version",1);
        return o;
    }
    public static void log(JSONObject state, String type, String id, String result) {
        JSONArray events=state.optJSONArray("events"); if(events==null) events=new JSONArray();
        JSONObject e=object(); put(e,"time",System.currentTimeMillis()); put(e,"type",type);
        put(e,"id",id); put(e,"result",result); events.put(e);
        while(events.length()>100) events.remove(0); put(state,"events",events);
    }
    public static boolean eligible(JSONObject item) { return item!=null && "active".equals(item.optString("status")) && !item.optBoolean("private"); }
    public File photoFile(String reference) throws IOException {
        if(reference==null || !reference.matches("photos/[a-fA-F0-9-]+\\.jpg")) throw new IOException("无效照片引用");
        return new File(context.getFilesDir(),reference);
    }
    public void collectPhotos(JSONObject state) {
        File folder=new File(context.getFilesDir(),"photos"); File[] files=folder.listFiles(); if(files==null)return;
        java.util.Set<String> used=new java.util.HashSet<>();
        used.add(settings(state).optString("photo"));
        for(int i=0;i<items(state).length();i++) used.add(items(state).optJSONObject(i).optString("photo"));
        // Pending deliveries reference a rendered bitmap, so their source photos are not required.
        for(File f:files) if(!used.contains("photos/"+f.getName())) f.delete();
    }
}
