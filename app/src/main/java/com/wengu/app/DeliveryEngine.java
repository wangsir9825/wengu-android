package com.wengu.app;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.*;
import android.view.WindowManager;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.*;

public final class DeliveryEngine {
    public interface Gateway { boolean allowed(); int id(int target); int apply(Bitmap image,int target)throws Exception; }
    private final Context context;
    private final Store store;
    private final Gateway gateway;
    public DeliveryEngine(Context c){this(c,new Gateway(){
        final WallpaperManager wm=WallpaperManager.getInstance(c);
        public boolean allowed(){return wm.isWallpaperSupported()&&wm.isSetWallpaperAllowed();}
        public int id(int target){return wm.getWallpaperId(target);}
        public int apply(Bitmap image,int target)throws Exception{return wm.setBitmap(image,new Rect(0,0,image.getWidth(),image.getHeight()),true,target);}
    });}
    public DeliveryEngine(Context c,Gateway gateway){context=c.getApplicationContext();store=new Store(c);this.gateway=gateway;}
    public static int[] size(Context c){Point p=new Point();((WindowManager)c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);return new int[]{Math.min(p.x,p.y),Math.max(p.x,p.y)};}
    public String apply(String selected,boolean automatic,long now) throws IOException {
        return applyInternal(selected,automatic,now,false);
    }
    /** Reapply the current sentence without treating a settings change as a review or resuming pause. */
    public String applyCurrentSettings(long now)throws IOException {
        return applyInternal(null,false,now,true);
    }
    private String applyInternal(String selected,boolean automatic,long now,boolean appearanceOnly)throws IOException {
        synchronized(Store.LOCK) {
            JSONObject state=store.load(),cfg=Store.settings(state),s=Store.session(state);ZoneId zone=ZoneId.systemDefault();
            boolean wasEnabled=cfg.optBoolean("enabled");
            if(appearanceOnly){selected=s.optString("current_id");if(!Store.eligible(Store.find(state,selected)))return "请先选择一条经验，预览并应用";}
            if(automatic&&!cfg.optBoolean("enabled"))return s.optString("status");
            if(automatic&&!ReviewPlanner.active(state,now,zone))return "静默时段，保留当前壁纸";
            JSONObject pending=s.optJSONObject("pending");
            if(appearanceOnly){s.remove("pending");pending=null;}
            if(automatic && externalChange(s,pending)) {
                Store.put(cfg,"enabled",false);Store.put(s,"status","检测到系统壁纸已改变，自动展示已暂停");
                Store.log(state,"external_change","","paused");store.save(state);return s.optString("status");
            }
            if(automatic && pending==null) {
                if(now-s.optLong("last_any")<ReviewPlanner.MIN_GAP)return "当前壁纸保留至少两小时";
                long today=ReviewPlanner.day(now,zone);int slot=ReviewPlanner.slot(state,now,zone);
                if(s.optLong("day",-1)==today&&s.optInt("last_slot",-1)>=slot)return "等待下一个展示时段";
            }
            if(!gateway.allowed()) {
                Store.put(cfg,"enabled",false);Store.put(s,"status","系统暂不允许设置壁纸，可在预览页导出图片");store.save(state);return s.optString("status");
            }
            // Keep the original intent if a retry needs fresh images after a settings/edit change.
            if(pending!=null&&pending.optBoolean("appearance_only")&&(automatic||selected==null||selected.equals(pending.optString("experience_id"))))appearanceOnly=true;
            if(pending!=null) {
                JSONObject item=Store.find(state,pending.optString("experience_id"));
                if(!Store.eligible(item)||item.optInt("version")!=pending.optInt("version")) {s.remove("pending");pending=null;store.save(state);}
            }
            if(!automatic&&selected!=null && pending!=null&&!selected.equals(pending.optString("experience_id"))) {s.remove("pending");pending=null;store.save(state);}
            if(!automatic&&pending!=null&&(pending.optInt("targets")!=cfg.optInt("target",2)||!pending.optString("style_signature").equals(styleSignature(cfg)))){s.remove("pending");pending=null;store.save(state);}
            if(pending==null) {
                List<JSONObject> candidates=ReviewPlanner.candidates(state,now,zone);JSONObject item=null;boolean advance=false;
                if(selected!=null){item=Store.find(state,selected);if(!automatic)advance=!appearanceOnly&&ReviewPlanner.readyForReview(state,item,now,zone);else for(JSONObject e:candidates)if(selected.equals(e.optString("id")))advance=true;}
                else if(!automatic){List<JSONObject> choices=ReviewPlanner.manualCandidates(state,now,zone);if(!choices.isEmpty()){item=choices.get(0);advance=ReviewPlanner.readyForReview(state,item,now,zone);}}
                else if(!candidates.isEmpty()){item=candidates.get(0);advance=true;}
                else {
                    // Maintenance slots may repeat established experiences without advancing their review stage.
                    List<JSONObject> pool=new ArrayList<>();JSONArray shown=s.optJSONArray("shown");Set<String> shownIds=new HashSet<>();
                    if(shown!=null)for(int i=0;i<shown.length();i++)shownIds.add(shown.optString(i));
                    boolean room=shownIds.size()<cfg.optInt("budget",4);
                    for(int i=0;i<Store.items(state).length();i++){JSONObject e=Store.items(state).optJSONObject(i);if(Store.eligible(e)&&e.optInt("stage",-1)>=0&&(shownIds.contains(e.optString("id"))||room&&e.optLong("due")>ReviewPlanner.day(now,zone))&&!e.optString("id").equals(s.optString("current_id")))pool.add(e);}
                    if(!pool.isEmpty()) {pool.sort(Comparator.comparing(e->e.optString("id")));item=pool.get(Math.floorMod(s.optInt("maintenance_cursor"),pool.size()));Store.put(s,"maintenance_cursor",s.optInt("maintenance_cursor")+1);}
                }
                if(!Store.eligible(item)) {
                    Store.put(s,"status",selected!=null?"这条经验已暂停、归档或设为私密，请先恢复参与展示":!automatic?ReviewPlanner.manualEmptyReason(state):"暂无到期或可轮换的经验，保留当前壁纸");
                    if(automatic)Store.put(s,"last_slot",ReviewPlanner.slot(state,now,zone));store.save(state);return s.optString("status");
                }
                JSONArray shown=s.optJSONArray("shown");boolean seen=false;if(shown!=null)for(int i=0;i<shown.length();i++)if(item.optString("id").equals(shown.optString(i)))seen=true;
                if(automatic&&!seen&&shown!=null&&shown.length()>=cfg.optInt("budget",4)){Store.put(s,"status","今日已达到展示数量上限，可在设置中调整或明天继续");store.save(state);return s.optString("status");}
                JSONObject renderCfg=Store.copy(cfg);
                if(cfg.optBoolean("random")&&item.optInt("theme",-1)<0&&item.optString("photo").isEmpty()&&cfg.optString("photo").isEmpty()) {
                    int previous=s.optInt("last_theme",-1);List<Integer> themes=new ArrayList<>();JSONArray history=s.optJSONArray("theme_history");
                    for(int i=0;i<WallpaperComposer.COLORS.length;i++){boolean recent=false;if(history!=null)for(int j=0;j<history.length();j++)if(history.optInt(j)==i)recent=true;if(!recent&&i!=previous)themes.add(i);}
                    if(themes.isEmpty())themes.add((previous+1)%WallpaperComposer.COLORS.length);
                    int theme=themes.get(new Random().nextInt(themes.size()));Store.put(renderCfg,"theme",theme);
                }
                pending=Store.object();Store.put(pending,"id",UUID.randomUUID().toString());Store.put(pending,"experience_id",item.optString("id"));
                Store.put(pending,"version",item.optInt("version"));Store.put(pending,"expected_stage",item.optInt("stage",-1));Store.put(pending,"advance",advance);
                Store.put(pending,"appearance_only",appearanceOnly);
                Store.put(pending,"style_signature",styleSignature(cfg));
                Store.put(pending,"targets",cfg.optInt("target",2));Store.put(pending,"done",0);Store.put(pending,"failures",0);Store.put(pending,"theme",renderCfg.optInt("theme"));
                int[] dimensions=size(context);boolean fallback=false;
                for(int target:new int[]{2,1})if((cfg.optInt("target",2)&target)!=0){
                    WallpaperComposer.Rendered r=WallpaperComposer.render(context,item,renderCfg,dimensions[0],dimensions[1],target);fallback|=r.photoFallback;
                    try{writeBitmap(r.bitmap,new File(context.getFilesDir(),"pending-"+target+".png"));}finally{r.bitmap.recycle();}
                }
                Store.put(pending,"photo_fallback",fallback);Store.put(s,"pending",pending);if(!automatic&&!appearanceOnly)Store.put(cfg,"enabled",true);Store.log(state,"attempt",item.optString("id"),pending.optString("id"));store.save(state);
            }
            if(!automatic&&!appearanceOnly){Store.put(cfg,"enabled",true);store.save(state);}
            int flags=pending.optInt("targets"),done=pending.optInt("done");String error="";
            for(int target:new int[]{2,1})if((flags&target)!=0&&(done&target)==0) {
                byte[] encoded=Files.readAllBytes(new File(context.getFilesDir(),"pending-"+target+".png").toPath());
                Bitmap image=BitmapFactory.decodeByteArray(encoded,0,encoded.length);
                if(image==null){error="待应用图片无法读取，请重新预览并应用";break;}
                try {
                    int id=gateway.apply(image,target);if(id<=0)id=gateway.id(target);
                    if(id<=0)throw new IOException("系统未确认设置结果");
                    done|=target;Store.put(pending,"done",done);Store.put(s,"owned_"+target,id);store.save(state);
                }catch(Exception e){error=(target==2?"锁屏":"桌面")+"设置失败："+friendly(e);}
                finally{image.recycle();}
            }
            if(done==flags) {
                ReviewPlanner.complete(state,pending,now,zone);Store.put(s,"current_id",pending.optString("experience_id"));Store.put(s,"current_targets",flags);
                JSONObject displayed=Store.find(state,pending.optString("experience_id"));if(displayed!=null&&!appearanceOnly)Store.put(displayed,"last_displayed",now);
                ReviewPlanner.candidates(state,now,zone);JSONArray shown=s.optJSONArray("shown");if(shown==null)shown=new JSONArray();boolean seen=false;for(int i=0;i<shown.length();i++)if(pending.optString("experience_id").equals(shown.optString(i)))seen=true;if(!seen)shown.put(pending.optString("experience_id"));Store.put(s,"shown",shown);
                Store.put(s,"last_any",now);Store.put(s,"last_slot",ReviewPlanner.slot(state,now,zone));Store.put(s,"last_success",now);
                String applied="壁纸已应用到"+(flags==1?"桌面":flags==2?"锁屏":"锁屏和桌面");
                Store.put(s,"status",applied+(pending.optBoolean("photo_fallback")?"；照片不可用，已使用内置背景":appearanceOnly?"":"，自动展示已开启"));
                Store.put(cfg,"enabled",appearanceOnly?wasEnabled:true);Store.put(s,"last_theme",pending.optInt("theme"));
                JSONArray history=s.optJSONArray("theme_history");if(history==null)history=new JSONArray();history.put(pending.optInt("theme"));while(history.length()>3)history.remove(0);Store.put(s,"theme_history",history);
                Files.copy(new File(context.getFilesDir(),"pending-"+((flags&2)!=0?2:1)+".png").toPath(),new File(context.getFilesDir(),"current-wallpaper.png").toPath(),StandardCopyOption.REPLACE_EXISTING);
                Store.log(state,appearanceOnly?"appearance":"delivered",pending.optString("experience_id"),pending.optBoolean("advance")?"stage_advanced":"maintenance");s.remove("pending");store.save(state);DisplayWorker.schedule(context);
            }else {
                int failures=pending.optInt("failures")+1;Store.put(pending,"failures",failures);
                Store.put(s,"status",(done==0?"": "部分成功。")+error+(failures>=3?"；自动展示已暂停，请手动重试":!cfg.optBoolean("enabled")?"；请手动重试失败的目标":"；将重试失败的目标"));
                if(failures>=3)Store.put(cfg,"enabled",false);
                Store.log(state,"failed",pending.optString("experience_id"),error);store.save(state);
                if(!automatic&&failures<3&&cfg.optBoolean("enabled"))DisplayWorker.retrySoon(context);
            }
            return s.optString("status");
        }
    }
    private static String styleSignature(JSONObject cfg){JSONObject style=Store.copy(cfg);for(String key:new String[]{"enabled","budget","new_limit","start_hour","end_hour","ui_style"})style.remove(key);return style.toString();}
    private boolean externalChange(JSONObject s,JSONObject pending) {
        int watched=s.optInt("current_targets",3);if(pending!=null)watched|=pending.optInt("targets");
        for(int target:new int[]{1,2}){if((watched&target)==0)continue;if(pending!=null&&(pending.optInt("targets")&target)!=0&&(pending.optInt("done")&target)==0)continue;int expected=s.optInt("owned_"+target,0);if(expected>0)try{if(gateway.id(target)!=expected)return true;}catch(Exception ignored){}}
        return false;
    }
    public String hide(String id)throws IOException {
        synchronized(Store.LOCK) {
            JSONObject state=store.load(),s=Store.session(state);int flags=0;
            if(id.equals(s.optString("current_id")))flags|=s.optInt("current_targets");
            JSONObject pending=s.optJSONObject("pending");if(pending!=null&&id.equals(pending.optString("experience_id"))){flags|=pending.optInt("done");s.remove("pending");}
            if(flags==0){store.save(state);return "内容已更新";}
            int[] dimensions=size(context);WallpaperComposer.Rendered r=WallpaperComposer.render(context,null,Store.settings(state),dimensions[0],dimensions[1],2);
            String error="";
            try{for(int target:new int[]{2,1})if((flags&target)!=0)try{int wallId=gateway.apply(r.bitmap,target);Store.put(s,"owned_"+target,wallId);}catch(Exception e){error="旧内容仍可能显示，请到系统壁纸设置中替换";}}
            finally{r.bitmap.recycle();}
            if(error.isEmpty()){s.remove("current_id");s.remove("current_targets");new File(context.getFilesDir(),"current-wallpaper.png").delete();}
            Store.put(s,"status",error.isEmpty()?"当前壁纸正文已清除":error);store.save(state);return s.optString("status");
        }
    }
    static void writeBitmap(Bitmap image,File destination)throws IOException {
        android.util.AtomicFile f=new android.util.AtomicFile(destination);FileOutputStream out=null;
        try{out=f.startWrite();if(!image.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("壁纸生成失败");f.finishWrite(out);}
        catch(Exception e){if(out!=null)f.failWrite(out);throw new IOException(e);}
    }
    private static String friendly(Exception e){if(e instanceof SecurityException)return "系统权限受限";return e.getMessage()==null?"请稍后重试":e.getMessage();}
}
