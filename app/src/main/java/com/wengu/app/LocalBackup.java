package com.wengu.app;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.work.*;
import java.io.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** User-selected public Documents folder. App-private storage is never a backup destination. */
public final class LocalBackup {
    static final Object LOCK=new Object();
    static final String PREFS="local-backup";
    private static final String WORK="wengu-local-backup";
    private static final int ACCESS=Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    private static final String HISTORY_PATTERN="温故-\\d{8}-\\d{6}-\\d{3}-[a-f0-9]{8}\\.zip";
    public static SharedPreferences preferences(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    public static boolean configured(Context c){return !preferences(c).getString("tree","").isEmpty();}
    public static boolean enabled(Context c){SharedPreferences p=preferences(c);return configured(c)&&p.getBoolean("enabled",true)&&!p.getBoolean("awaiting_restore",false);}
    /** Latest acknowledged ZIP covers every current record; pausing future backups is independent. */
    public static boolean coverageCurrent(Context c){
        Map<String,?> values=preferences(c).getAll();return covered(values);
    }
    /** No Store/file/provider queries: suitable for a persistent home-page status line. */
    public static String summary(Context c){return summary(preferences(c).getAll());}
    private static String summary(Map<String,?> values){
        if(string(values,"tree").isEmpty())return "未设置本地备份";
        if(bool(values,"awaiting_restore",false))return "待恢复历史备份";
        if(!bool(values,"enabled",true))return string(values,"error").isEmpty()?"备份已暂停":"备份已暂停 · 上次备份失败";
        if(!string(values,"error").isEmpty())return "备份失败";
        if(!snapshotCurrent(values))return "有未备份内容";
        if(!bool(values,"backed_include_private",false))return number(values,"store_private_count",0)>0?"非私密已备份 · 私密未备份":"已备份 · 不含私密";
        return "已备份";
    }
    private static boolean covered(Map<String,?> values){
        return !string(values,"tree").isEmpty()&&!bool(values,"awaiting_restore",false)&&string(values,"error").isEmpty()
                &&snapshotCurrent(values)&&(bool(values,"backed_include_private",false)||number(values,"store_private_count",0)==0);
    }
    private static boolean snapshotCurrent(Map<String,?> values){
        return bool(values,"store_known",false)&&bool(values,"backed_known",false)&&!string(values,"store_epoch").isEmpty()
                &&number(values,"last",0)>0&&!string(values,"last_uri").isEmpty()
                &&string(values,"store_epoch").equals(string(values,"backed_epoch"))
                &&number(values,"store_revision",-1)==number(values,"backed_revision",-2)
                &&bool(values,"include_private",true)==bool(values,"backed_include_private",false);
    }
    private static String string(Map<String,?> values,String key){Object value=values.get(key);return value instanceof String?(String)value:"";}
    private static boolean bool(Map<String,?> values,String key,boolean fallback){Object value=values.get(key);return value instanceof Boolean?(Boolean)value:fallback;}
    private static long number(Map<String,?> values,String key,long fallback){Object value=values.get(key);return value instanceof Number?((Number)value).longValue():fallback;}

    public static void configure(Context c,Uri tree,int flags)throws Exception {
        synchronized(LOCK){
            String id=validateTree(tree);
            int access=flags&ACCESS;
            if(access!=ACCESS)throw new IOException("备份文件夹需要读写权限，请重新选择");
            c.getContentResolver().takePersistableUriPermission(tree,access);
            requirePermission(c,tree);
            // Verify the folder exists and remains writable before replacing a working selection.
            requireFolder(c,tree);
            SharedPreferences p=preferences(c);String previous=p.getString("tree","");
            SharedPreferences.Editor edit=p.edit().putString("tree",tree.toString()).putString("folder",id).putBoolean("enabled",true).remove("error").remove("warning").remove("awaiting_restore").remove("restore_confirmed").remove("restore_epoch").remove("availability_error").remove("availability_last_uri");
            if(!tree.toString().equals(previous))edit.remove("digest").remove("last").remove("last_uri").remove("backed_known").remove("backed_epoch").remove("backed_revision").remove("backed_include_private").remove("backed_count").remove("backed_private_count");
            if(!edit.commit())throw new IOException("备份位置未保存，请重试");
            // Guard here as well as in write(): a background worker can run before the UI's prompt.
            guardEmptyReinstallation(c,p,listUnlocked(c,tree));
            if(!previous.isEmpty()&&!previous.equals(tree.toString())){
                try{c.getContentResolver().releasePersistableUriPermission(Uri.parse(previous),ACCESS);}catch(Exception ignored){}
            }
        }
    }

    public static void request(Context c){
        if(!enabled(c))return;
        Map<String,?> values=preferences(c).getAll();
        // Session-only saves should not rebuild an unchanged ZIP, including intentional exclusions.
        if(string(values,"error").isEmpty()&&snapshotCurrent(values))return;
        try{
            OneTimeWorkRequest work=new OneTimeWorkRequest.Builder(LocalBackupWorker.class)
                    .setInitialDelay(5,TimeUnit.SECONDS).setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build();
            WorkManager.getInstance(c).enqueueUniqueWork(WORK,ExistingWorkPolicy.REPLACE,work);
        }catch(Exception e){preferences(c).edit().putString("error","自动备份暂未排入，请点立即备份").apply();}
    }
    public static String afterSave(Context c,String saved){
        if(!configured(c))return saved+" · 未设置本地备份";
        if(preferences(c).getBoolean("awaiting_restore",false))return saved+" · 待恢复历史备份，新增内容尚未备份";
        if(!enabled(c))return saved+" · 备份已暂停"+(coverageCurrent(c)?"（当前内容已有备份）":"，本次内容尚未备份");
        try{write(c);return saved+" · "+summary(c);}catch(Exception e){return saved+"；本地备份未完成，请到备份页面重试";}
    }
    /** Background-only availability check. Call outside Store.LOCK to preserve lock ordering. */
    public static void verifyAvailable(Context c){
        if(Thread.holdsLock(Store.LOCK))throw new IllegalStateException("verifyAvailable must run outside Store.LOCK");
        synchronized(LOCK){
            SharedPreferences p=preferences(c);String last=p.getString("last_uri","");
            // An unconfigured installation or one without a completed file is not a failed backup.
            if(!configured(c)||p.getLong("last",0)<=0||last.isEmpty())return;
            Uri tree=Uri.parse(p.getString("tree",""));
            try{validateTree(tree);requirePermission(c,tree);requireFolder(c,tree);}
            catch(Exception e){p.edit().putBoolean("backed_known",false).putString("availability_error","folder").putString("error",failureMessage(e)).commit();return;}
            if(!exists(c,Uri.parse(last))){
                boolean first=!"file".equals(p.getString("availability_error",""))||!last.equals(p.getString("availability_last_uri",""));
                SharedPreferences.Editor edit=p.edit().putBoolean("backed_known",false).putString("availability_error","file").putString("availability_last_uri",last);
                if(first||p.getString("error","").isEmpty())edit.putString("error",enabled(c)?"上次备份文件不可用，正在重新备份":"上次备份文件不可用，请点立即备份");
                edit.commit();
                // One repair request per loss, so repeated checks do not reset a worker's backoff.
                if(first)request(c);
            }else if(!p.getString("availability_error","").isEmpty()){
                // Restored access alone does not prove the bytes. A worker must verify/export first.
                p.edit().remove("availability_error").remove("availability_last_uri").commit();request(c);
            }
        }
    }
    /** Call after a user-confirmed restore, including an intentionally empty backup. */
    public static void confirmRestore(Context c)throws IOException {
        synchronized(LOCK){
            String restoredEpoch=Store.epoch(new Store(c).load());
            SharedPreferences p=preferences(c);SharedPreferences.Editor edit=p.edit().putBoolean("restore_confirmed",true).putString("restore_epoch",restoredEpoch).remove("awaiting_restore").remove("error");
            if(p.getBoolean("awaiting_restore",false))edit.putBoolean("enabled",true);
            if(!edit.commit())throw new IOException("恢复记录已保存，但备份确认未保存，请重试");
        }
    }
    public static String status(Context c){
        Map<String,?> values=preferences(c).getAll();if(string(values,"tree").isEmpty())return "尚未选择本地备份文件夹";
        String result=summary(values),error=string(values,"error");if(!error.isEmpty())result+="\n"+error;
        long time=number(values,"last",0);result+=time==0?"\n尚未完成首次备份":"\n上次完成 · "+new SimpleDateFormat("M月d日 HH:mm",Locale.CHINA).format(new Date(time));
        if(!bool(values,"include_private",true)&&number(values,"store_private_count",0)>0)result+="\n当前设置不备份私密经验（"+number(values,"store_private_count",0)+" 条）";
        String warning=string(values,"warning");return warning.isEmpty()?result:result+"\n"+warning;
    }

    public static String write(Context c)throws Exception {
        synchronized(LOCK){
            SharedPreferences p=preferences(c);if(!configured(c))throw new IOException("请先选择本地备份文件夹");
            Uri tree=Uri.parse(p.getString("tree","")),document=null;File temporary=null;boolean completed=false;
            String exportedEpoch;long exportedRevision;boolean includePrivate;int exportedCount,privateCount;
            try {
                validateTree(tree);requirePermission(c,tree);requireFolder(c,tree);
                List<Snapshot> versions=listUnlocked(c,tree);
                temporary=File.createTempFile("wengu-backup-",".zip",c.getCacheDir());
                synchronized(Store.LOCK){
                    // Use the same Store snapshot for the empty-data guard and export.
                    if(guardEmptyReinstallation(c,p,versions)||p.getBoolean("awaiting_restore",false))throw new RestoreRequired();
                    org.json.JSONObject snapshot=new Store(c).load();exportedEpoch=Store.epoch(snapshot);exportedRevision=Store.revision(snapshot);
                    includePrivate=p.getBoolean("include_private",true);exportedCount=Store.items(snapshot).length();privateCount=0;
                    for(int i=0;i<Store.items(snapshot).length();i++)if(Store.items(snapshot).optJSONObject(i).optBoolean("private"))privateCount++;
                    try(OutputStream out=new FileOutputStream(temporary)){Backup.export(c,out,includePrivate);}
                }
                String digest=digest(new FileInputStream(temporary));
                Uri previous=Uri.parse(p.getString("last_uri",""));
                if(digest.equals(p.getString("digest",""))&&exists(c,previous)){
                    // Do not claim an externally edited or damaged file is still a current backup.
                    if(digest.equals(digest(c.getContentResolver().openInputStream(previous)))){
                        if(!acknowledge(p.edit().remove("error"),exportedEpoch,exportedRevision,includePrivate,exportedCount,privateCount).commit())throw new IOException("备份文件已核实，但备份状态未保存，请重试");
                        return "本地备份已是最新";
                    }
                }
                Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                String name="温故-"+new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.CHINA).format(new Date())+"-"+UUID.randomUUID().toString().substring(0,8)+".zip";
                document=DocumentsContract.createDocument(c.getContentResolver(),parent,"application/zip",".正在备份-"+name);
                if(document==null)throw new IOException("无法在所选文件夹创建备份");
                try(InputStream in=new FileInputStream(temporary);OutputStream out=c.getContentResolver().openOutputStream(document,"w")){
                    if(out==null)throw new IOException("无法写入备份文件");byte[] buffer=new byte[16384];int n;
                    while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                    out.flush();if(out instanceof FileOutputStream)((FileOutputStream)out).getFD().sync();
                }
                // Verify the exact bytes and the restore manifest before exposing a completed ZIP.
                if(!digest.equals(digest(c.getContentResolver().openInputStream(document))))throw new IOException("备份写入校验失败，请重试");
                Backup.ImportPlan checked;
                try(InputStream in=c.getContentResolver().openInputStream(document)){if(in==null)throw new IOException("无法校验备份");checked=Backup.inspect(c,in);}
                checked.discard();
                Uri finished=DocumentsContract.renameDocument(c.getContentResolver(),document,name);
                if(finished==null)throw new IOException("备份完成前无法重命名，请换一个本地文件夹");
                document=finished;completed=true;
                if(!acknowledge(p.edit().putLong("last",System.currentTimeMillis()).putString("last_uri",finished.toString()).putString("digest",digest).remove("error").remove("warning").remove("awaiting_restore").remove("restore_confirmed"),exportedEpoch,exportedRevision,includePrivate,exportedCount,privateCount).commit()){
                    // The full public file is recoverable even if private preference storage fails.
                    throw new IOException("备份文件已保存，但备份状态未保存，请重新打开备份页面");
                }
                prune(c,tree,finished,p);
                return p.getString("warning","").isEmpty()?"已备份到本地，卸载后文件仍保留":"已备份到本地；旧版本清理未完成";
            }catch(Exception e){
                // Once renamed, never delete a valid backup because bookkeeping or rotation failed.
                if(document!=null&&!completed)try{DocumentsContract.deleteDocument(c.getContentResolver(),document);}catch(Exception ignored){}
                p.edit().putString("error",failureMessage(e)).commit();throw e;
            }finally{if(temporary!=null)temporary.delete();}
        }
    }
    private static SharedPreferences.Editor acknowledge(SharedPreferences.Editor edit,String epoch,long revision,boolean includePrivate,int count,int privateCount){
        // Never copy the current Store cache here: it may already represent a later save.
        return edit.remove("availability_error").remove("availability_last_uri").remove("restore_confirmed").remove("restore_epoch").putBoolean("backed_known",true).putString("backed_epoch",epoch).putLong("backed_revision",revision)
                .putBoolean("backed_include_private",includePrivate).putInt("backed_count",includePrivate?count:count-privateCount)
                .putInt("backed_private_count",includePrivate?privateCount:0);
    }

    private static boolean guardEmptyReinstallation(Context c,SharedPreferences p,List<Snapshot> versions)throws Exception {
        if(versions.isEmpty())return false;
        org.json.JSONObject current=new Store(c).load();String epoch=Store.epoch(current),backedEpoch=p.getString("backed_epoch","");
        // Missing public files must not turn a known installation's intentional empty library
        // into a reinstallation. Conversely, load the Store first so a vanished private data file
        // cannot reuse stale preference metadata to silently overwrite historical versions.
        boolean confirmedHere=!epoch.isEmpty()&&epoch.equals(backedEpoch)&&p.getLong("backed_revision",-1)>=0&&p.getLong("last",0)>0;
        // An old unbound restore flag cannot establish identity, even after a new empty Store
        // file is created. Empty migrated restores must be confirmed again by the user.
        boolean legacyHere=backedEpoch.isEmpty()&&!p.getBoolean("restore_confirmed",false)&&p.getBoolean("store_file_present",false)&&p.getLong("last",0)>0&&exists(c,Uri.parse(p.getString("last_uri","")));
        boolean restoredHere=p.getBoolean("restore_confirmed",false)&&!epoch.isEmpty()&&epoch.equals(p.getString("restore_epoch",""));
        boolean established=restoredHere||confirmedHere||legacyHere;
        if(established||Store.items(current).length()>0)return false;
        p.edit().putBoolean("enabled",false).putBoolean("awaiting_restore",true).putString("error","请先恢复之前的经验，历史备份已保留").commit();
        return true;
    }
    private static void prune(Context c,Uri tree,Uri newest,SharedPreferences p){
        try{
            List<Snapshot> versions=listUnlocked(c,tree);int retained=1;boolean failed=false;
            // Always retain the newly completed file, including when the device clock moved backwards.
            for(Snapshot version:versions){if(version.uri.equals(newest))continue;if(retained++<10)continue;
                try{if(!DocumentsContract.deleteDocument(c.getContentResolver(),version.uri))failed=true;}catch(Exception e){failed=true;}}
            if(failed)p.edit().putString("warning","当前备份已完成，部分旧版本未能清理").commit();
        }catch(Exception e){p.edit().putString("warning","当前备份已完成，旧版本清理未完成").commit();}
    }
    private static String validateTree(Uri tree)throws IOException {
        if(tree==null||!"content".equals(tree.getScheme())||!"com.android.externalstorage.documents".equals(tree.getAuthority())||!DocumentsContract.isTreeUri(tree))throw new IOException("请选择手机本地 Documents 中的文件夹");
        String id;
        try{id=DocumentsContract.getTreeDocumentId(tree);}catch(Exception e){throw new IOException("文件夹地址无效，请重新选择");}
        int colon=id.indexOf(':');if(colon<1||colon==id.length()-1)throw new IOException("请在 Documents 中新建「温故备份」文件夹");
        String path=id.substring(colon+1);String lower=path.toLowerCase(Locale.ROOT);
        if(!(lower.equals("documents")||lower.startsWith("documents/")))throw new IOException("请选择手机 Documents 中的文件夹，不要选择手机根目录或 Android 目录");
        for(String part:path.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals("..")||part.indexOf('\\')>=0)throw new IOException("文件夹地址无效，请重新选择");
        return id;
    }
    private static void requirePermission(Context c,Uri tree){
        for(UriPermission permission:c.getContentResolver().getPersistedUriPermissions())if(tree.equals(permission.getUri())&&permission.isReadPermission()&&permission.isWritePermission())return;
        throw new SecurityException("文件夹权限失效，请重新选择");
    }
    private static void requireFolder(Context c,Uri tree)throws IOException {
        Uri folder=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        try(Cursor cursor=c.getContentResolver().query(folder,new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS},null,null,null)){
            if(cursor==null||!cursor.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0)))throw new IOException("备份文件夹不存在，请重新选择");
            if((cursor.getInt(1)&DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE)==0)throw new IOException("这个文件夹无法创建备份，请重新选择");
        }
    }
    private static boolean exists(Context c,Uri uri){
        if(uri.toString().isEmpty())return false;
        try(Cursor cursor=c.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){return cursor!=null&&cursor.moveToFirst();}catch(Exception e){return false;}
    }
    public static class Snapshot {public final String name;public final Uri uri;Snapshot(String n,Uri u){name=n;uri=u;}}
    public static List<Snapshot> list(Context c)throws Exception {
        synchronized(LOCK){
            if(!configured(c))throw new IOException("请先选择之前的本地备份文件夹");Uri tree=Uri.parse(preferences(c).getString("tree",""));
            try{validateTree(tree);requirePermission(c,tree);requireFolder(c,tree);return listUnlocked(c,tree);}
            catch(Exception e){preferences(c).edit().putString("error",failureMessage(e)).commit();throw e;}
        }
    }
    private static List<Snapshot> listUnlocked(Context c,Uri tree)throws Exception {
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));List<Snapshot> result=new ArrayList<>();
        try(Cursor cursor=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(cursor==null)throw new IOException("无法读取备份文件夹");
            while(cursor.moveToNext()){String name=cursor.getString(1);if(name!=null&&name.matches(HISTORY_PATTERN)&&!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)))result.add(new Snapshot(name,DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0))));}
        }
        result.sort((a,b)->b.name.compareTo(a.name));return result;
    }
    private static String digest(InputStream input)throws Exception {
        if(input==null)throw new IOException("无法读取备份文件，请重试");
        try(InputStream in=input){MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);return hex(digest.digest());}
    }
    static boolean retryable(Exception e){return e instanceof IOException&&!(e instanceof RestoreRequired);}
    static String failureMessage(Exception e){return e instanceof SecurityException?"文件夹权限失效，请重新选择":e.getMessage()==null?"请重新选择文件夹后重试":e.getMessage();}
    private static final class RestoreRequired extends IOException {RestoreRequired(){super("请先恢复之前的经验，历史备份已保留");}}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
