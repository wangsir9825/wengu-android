package com.wengu.app;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

public final class Backup {
    private static final long LIMIT=100*1024*1024L;
    public static class ImportPlan {
        public final JSONObject data;
        public final File directory;
        public int added,duplicates,conflicts;
        ImportPlan(JSONObject data,File dir){this.data=data;directory=dir;}
        public void discard(){File[] files=directory.listFiles();if(files!=null)for(File f:files){if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File c:children)c.delete();}f.delete();}directory.delete();}
    }
    public static void export(Context c,OutputStream output,boolean includePrivate)throws Exception {
        synchronized(Store.LOCK) {
            Store store=new Store(c);JSONObject state=store.load(),data=Store.object();JSONArray exported=new JSONArray();Set<String> photos=new TreeSet<>();
            for(int i=0;i<Store.items(state).length();i++){JSONObject e=Store.items(state).optJSONObject(i);if(!includePrivate&&e.optBoolean("private"))continue;exported.put(e);if(!e.optString("photo").isEmpty())photos.add(e.optString("photo"));}
            if(!Store.settings(state).optString("photo").isEmpty())photos.add(Store.settings(state).optString("photo"));
            Store.put(data,"schema_version",1);Store.put(data,"items",exported);Store.put(data,"settings",Store.settings(state));
            Map<String,byte[]> contents=new TreeMap<>();contents.put("experiences.json",data.toString().getBytes(StandardCharsets.UTF_8));
            long total=contents.get("experiences.json").length;
            for(String path:photos){File file=store.photoFile(path);if(!file.isFile())throw new IOException("有照片副本缺失，请重新选择该照片后备份");total+=file.length();if(total>LIMIT)throw new IOException("备份超过 100MB，请减少照片后再导出");contents.put(path,Files.readAllBytes(file.toPath()));}
            JSONObject manifest=Store.object();for(Map.Entry<String,byte[]> entry:contents.entrySet())Store.put(manifest,entry.getKey(),sha(entry.getValue()));
            try(ZipOutputStream zip=new ZipOutputStream(output)){for(Map.Entry<String,byte[]> entry:contents.entrySet()){ZipEntry zipEntry=new ZipEntry(entry.getKey());zipEntry.setTime(0);zip.putNextEntry(zipEntry);zip.write(entry.getValue());zip.closeEntry();}ZipEntry zipManifest=new ZipEntry("manifest.json");zipManifest.setTime(0);zip.putNextEntry(zipManifest);zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
        }
    }
    public static ImportPlan inspect(Context c,InputStream source)throws Exception {
        File dir=new File(c.getCacheDir(),"restore-"+UUID.randomUUID());dir.mkdirs();ImportPlan plan=null;
        try {
            Map<String,String> hashes=new HashMap<>();Set<String> names=new HashSet<>();long size=0;int count=0;
            try(ZipInputStream zip=new ZipInputStream(source)){
                ZipEntry entry;byte[] buffer=new byte[8192];
                while((entry=zip.getNextEntry())!=null){String name=entry.getName();
                    if(++count>2000||!names.add(name)||!name.matches("experiences\\.json|manifest\\.json|photos/[a-fA-F0-9-]+\\.jpg"))throw new IOException("备份包含无效或重复路径");
                    File dest=new File(dir,name);dest.getParentFile().mkdirs();MessageDigest digest=MessageDigest.getInstance("SHA-256");long entryBytes=0;
                    try(OutputStream out=new FileOutputStream(dest)){int n;while((n=zip.read(buffer))!=-1){size+=n;entryBytes+=n;if(size>LIMIT||name.endsWith(".json")&&entryBytes>8*1024*1024L)throw new IOException("备份内容过大");digest.update(buffer,0,n);out.write(buffer,0,n);}}
                    hashes.put(name,hex(digest.digest()));zip.closeEntry();
                }
            }
            JSONObject data=new JSONObject(new String(Files.readAllBytes(new File(dir,"experiences.json").toPath()),StandardCharsets.UTF_8));
            JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(dir,"manifest.json").toPath()),StandardCharsets.UTF_8));
            if(data.optInt("schema_version")!=1)throw new IOException("不支持此备份版本");
            for(String name:names)if(!name.equals("manifest.json")&&!hashes.get(name).equals(manifest.optString(name)))throw new IOException("备份校验失败："+name);
            for(Iterator<String> it=manifest.keys();it.hasNext();)if(!names.contains(it.next()))throw new IOException("备份缺少文件");
            JSONArray items=data.optJSONArray("items");if(items==null||items.length()>10000)throw new IOException("经验数量或格式无效");
            Set<String> ids=new HashSet<>();Store store=new Store(c);
            JSONObject local=store.load();
            plan=new ImportPlan(data,dir);
            for(int i=0;i<items.length();i++) {
                JSONObject e=items.optJSONObject(i);if(e==null||!e.optString("id").matches("[a-fA-F0-9-]{20,50}")||!ids.add(e.optString("id")))throw new IOException("经验 ID 无效或重复");
                WallpaperComposer.validate(e);if(!Arrays.asList("active","paused","archived").contains(e.optString("status"))||e.optInt("stage",-1)<-1||e.optInt("stage",-1)>5)throw new IOException("经验状态无效");
                String photo=e.optString("photo");if(!photo.isEmpty()){store.photoFile(photo);if(!names.contains(photo))throw new IOException("备份缺少照片");}
                JSONObject current=Store.find(local,e.optString("id"));if(current==null)plan.added++;else if(current.toString().equals(e.toString()))plan.duplicates++;else plan.conflicts++;
            }
            JSONObject cfg=data.optJSONObject("settings");
            if(cfg==null||cfg.optInt("target")<1||cfg.optInt("target")>3||cfg.optInt("budget")<1||cfg.optInt("budget")>8||cfg.optInt("start_hour")<0||cfg.optInt("end_hour")>24||cfg.optInt("end_hour")-cfg.optInt("start_hour")<2||cfg.optInt("font")<22||cfg.optInt("font")>40)throw new IOException("展示设置无效");
            if(!cfg.optString("photo").isEmpty()){store.photoFile(cfg.optString("photo"));if(!names.contains(cfg.optString("photo")))throw new IOException("全局背景照片缺失");}
            return plan;
        }catch(Exception e){if(plan==null)plan=new ImportPlan(Store.object(),dir);plan.discard();throw e;}
    }
    /** mode: 0 keep local; 1 replace conflicts; 2 make copies. Device settings stay local. */
    public static void restore(Context c,ImportPlan plan,int mode)throws Exception {
        synchronized(Store.LOCK) {
            Store store=new Store(c);JSONObject state=store.load();JSONArray incoming=plan.data.optJSONArray("items");
            List<File> created=new ArrayList<>();Map<String,String> renamedPhotos=new HashMap<>();
            try {
                for(int i=0;i<incoming.length();i++) {
                    JSONObject imported=Store.copy(incoming.optJSONObject(i));JSONObject existing=Store.find(state,imported.optString("id"));
                    if(existing!=null){if(existing.toString().equals(imported.toString())||mode==0)continue;if(mode==2){Store.put(imported,"id",UUID.randomUUID().toString());Store.put(imported,"stage",-1);Store.put(imported,"due",java.time.LocalDate.now().toEpochDay());Store.put(imported,"last_success",0);}}
                    String photo=imported.optString("photo");
                    if(!photo.isEmpty()) {
                        String newRef=renamedPhotos.get(photo);if(newRef==null){newRef=photo;File original=store.photoFile(photo);byte[] sourceBytes=Files.readAllBytes(new File(plan.directory,photo).toPath());if(original.isFile()&&!sha(Files.readAllBytes(original.toPath())).equals(sha(sourceBytes)))newRef="photos/"+UUID.randomUUID()+".jpg";File destination=store.photoFile(newRef);destination.getParentFile().mkdirs();if(!destination.isFile()){Files.write(destination.toPath(),sourceBytes);created.add(destination);}renamedPhotos.put(photo,newRef);}Store.put(imported,"photo",newRef);
                    }
                    if(existing!=null&&mode==1){for(int j=0;j<Store.items(state).length();j++)if(Store.items(state).optJSONObject(j).optString("id").equals(imported.optString("id"))){Store.items(state).remove(j);break;}}
                    Store.items(state).put(imported);
                }
                JSONObject restoredSettings=Store.copy(plan.data.optJSONObject("settings"));String globalPhoto=restoredSettings.optString("photo");
                if(!globalPhoto.isEmpty()) {
                    String newRef=renamedPhotos.get(globalPhoto);
                    if(newRef==null){newRef=globalPhoto;File existingPhoto=store.photoFile(globalPhoto);byte[] bytes=Files.readAllBytes(new File(plan.directory,globalPhoto).toPath());if(existingPhoto.isFile()&&!sha(Files.readAllBytes(existingPhoto.toPath())).equals(sha(bytes)))newRef="photos/"+UUID.randomUUID()+".jpg";File destination=store.photoFile(newRef);destination.getParentFile().mkdirs();if(!destination.isFile()){Files.write(destination.toPath(),bytes);created.add(destination);}}
                    Store.put(restoredSettings,"photo",newRef);
                }
                Store.put(state,"settings",restoredSettings);
                // A restore never silently changes the system wallpaper or resumes background rotation.
                Store.put(Store.settings(state),"enabled",false);Store.session(state).remove("pending");
                Store.put(Store.session(state),"status","备份已导入，确认内容后可重新开启自动展示");Store.log(state,"restore","","success");store.save(state);store.collectPhotos(state);DisplayWorker.schedule(c);
            }catch(Exception e){for(File f:created)f.delete();throw e;}
            finally{plan.discard();}
        }
    }
    private static String sha(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
}
