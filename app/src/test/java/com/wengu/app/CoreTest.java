package com.wengu.app;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.zip.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,shadows=CoreTest.PortableAtomicFile.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CoreTest {
    // Android uses Linux rename-over-existing semantics. java.io.File.renameTo does
    // not replace an existing destination on the Windows JVM used by Robolectric.
    @org.robolectric.annotation.Implements(android.util.AtomicFile.class)
    public static class PortableAtomicFile {
        @org.robolectric.annotation.RealObject android.util.AtomicFile real;
        @org.robolectric.annotation.Implementation protected void finishWrite(FileOutputStream stream) {
            if(stream==null)return;
            try {stream.flush();stream.getFD().sync();stream.close();java.nio.file.Files.move(new File(real.getBaseFile()+".new").toPath(),real.getBaseFile().toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
            catch(Exception e){throw new RuntimeException(e);}
        }
    }
    final ZoneId zone=ZoneId.of("Asia/Shanghai");
    long at(int day,int hour){return LocalDate.of(2026,10,day).atTime(hour,0).atZone(zone).toInstant().toEpochMilli();}
    JSONObject entry(){JSONObject e=Store.newExperience();Store.put(e,"summary","不确定时，先做一次小规模验证。");return e;}
    @Before public void reset()throws Exception{Context c=RuntimeEnvironment.getApplication();new Store(c).save(Store.fresh());TimeZone.setDefault(TimeZone.getTimeZone(zone));}
    @Test public void calendarIntervalsAndIdempotence() {
        JSONObject state=Store.fresh(),e=entry();Store.items(state).put(e);
        int[] days={3,4,7,14,28};int[] next={4,7,14,28,27};
        for(int i=0;i<days.length;i++){JSONObject a=Store.object();Store.put(a,"experience_id",e.optString("id"));Store.put(a,"advance",true);Store.put(a,"expected_stage",i-1);
            ReviewPlanner.complete(state,a,at(days[i],12),zone);assertEquals(i,e.optInt("stage"));
            long expected=i==4?LocalDate.of(2026,11,27).toEpochDay():LocalDate.of(2026,10,next[i]).toEpochDay();assertEquals(expected,e.optLong("due"));
            ReviewPlanner.complete(state,a,at(days[i],13),zone);assertEquals(i,e.optInt("stage"));}
    }
    @Test public void backlogBoundAndExclusions() {
        JSONObject state=Store.fresh();for(int i=0;i<30;i++){JSONObject e=entry();Store.put(e,"stage",2);Store.put(e,"due",LocalDate.of(2026,9,1).toEpochDay()+i);Store.items(state).put(e);}
        JSONObject privateItem=entry();Store.put(privateItem,"private",true);Store.items(state).put(privateItem);
        JSONObject paused=entry();Store.put(paused,"status","paused");Store.items(state).put(paused);
        List<JSONObject> candidates=ReviewPlanner.candidates(state,at(3,8),zone);assertEquals(4,candidates.size());
        assertEquals(LocalDate.of(2026,9,1).toEpochDay(),candidates.get(0).optLong("due"));
        JSONArray shown=new JSONArray();for(JSONObject e:candidates)shown.put(e.optString("id"));Store.put(Store.session(state),"shown",shown);
        assertTrue(ReviewPlanner.candidates(state,at(3,20),zone).isEmpty());
        assertEquals(4,ReviewPlanner.candidates(state,at(4,8),zone).size());
    }
    @Test public void silenceDwellAndDefaultSlots() {
        JSONObject state=Store.fresh();Store.put(Store.settings(state),"enabled",true);
        assertFalse(ReviewPlanner.active(state,at(3,23),zone));assertTrue(ReviewPlanner.active(state,at(3,8),zone));
        assertEquals(0,ReviewPlanner.slot(state,at(3,8),zone));assertEquals(1,ReviewPlanner.slot(state,at(3,12),zone));assertEquals(3,ReviewPlanner.slot(state,at(3,20),zone));
        Store.put(Store.session(state),"last_any",at(3,11));assertEquals(at(3,16),ReviewPlanner.nextAttempt(state,at(3,11),zone));
        assertEquals(at(4,8),ReviewPlanner.nextAttempt(state,at(3,23),zone));
    }
    @Test public void maintenanceDoesNotAdvanceAndTimezoneCannotRepeat() {
        JSONObject state=Store.fresh(),e=entry();Store.put(e,"stage",2);Store.put(e,"last_success",at(3,10));Store.put(e,"due",LocalDate.of(2026,10,3).toEpochDay());Store.items(state).put(e);
        assertTrue(ReviewPlanner.candidates(state,at(3,22),zone).isEmpty());
        JSONObject a=Store.object();Store.put(a,"experience_id",e.optString("id"));Store.put(a,"advance",false);ReviewPlanner.complete(state,a,at(4,8),zone);assertEquals(2,e.optInt("stage"));
    }
    @Test public void failedSecondTargetOnlyRetriesAndAdvancesOnce()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),e=entry();Store.items(state).put(e);Store.put(Store.settings(state),"target",3);store.save(state);
        class Fake implements DeliveryEngine.Gateway {int lockCalls,homeCalls;boolean fail=true;public boolean allowed(){return true;}public int id(int flag){return flag==2?77:88;}public int apply(Bitmap b,int flag)throws Exception{if(flag==2){lockCalls++;return 77;}homeCalls++;if(fail)throw new IOException("test failure");return 88;}}
        assertEquals("Persistent store: "+c.getFilesDir()+" "+Arrays.toString(c.getFilesDir().list()),state.toString(),store.load().toString());
        Fake fake=new Fake();DeliveryEngine engine=new DeliveryEngine(c,fake);String message=engine.apply(e.optString("id"),false,at(3,12));
        assertNotNull(message+" "+store.load(),Store.find(store.load(),e.optString("id")));
        assertEquals(-1,Store.find(store.load(),e.optString("id")).optInt("stage"));assertEquals(2,Store.session(store.load()).optJSONObject("pending").optInt("done"));
        assertTrue(Store.settings(store.load()).optBoolean("enabled"));
        fake.fail=false;engine.apply(e.optString("id"),false,at(3,13));assertEquals(1,fake.lockCalls);assertEquals(2,fake.homeCalls);
        assertEquals(0,Store.find(store.load(),e.optString("id")).optInt("stage"));
        engine.apply(e.optString("id"),false,at(3,14));assertEquals(0,Store.find(store.load(),e.optString("id")).optInt("stage"));
    }
    @Test public void changedTargetCancelsOldPartialDelivery()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),e=entry();Store.items(state).put(e);Store.put(Store.settings(state),"target",3);store.save(state);
        class Fake implements DeliveryEngine.Gateway {boolean fail=true;int lockCalls;public boolean allowed(){return true;}public int id(int flag){return flag==2?90:91;}public int apply(Bitmap b,int flag)throws Exception{if(flag==2){lockCalls++;return 90;}if(fail)throw new IOException("temporary failure");return 91;}}
        Fake fake=new Fake();DeliveryEngine engine=new DeliveryEngine(c,fake);engine.apply(e.optString("id"),false,at(3,12));
        state=store.load();Store.put(Store.settings(state),"target",1);store.save(state);fake.fail=false;
        engine.apply(e.optString("id"),false,at(3,13));assertEquals(1,fake.lockCalls);assertNull(Store.session(store.load()).optJSONObject("pending"));assertEquals(1,Store.session(store.load()).optInt("current_targets"));assertEquals(0,Store.find(store.load(),e.optString("id")).optInt("stage"));
    }
    @Test public void manualNextCanShowOtherNewEntryAfterAutomaticLimits()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),first=entry(),second=entry(),secret=entry(),paused=entry();
        Store.put(Store.settings(state),"budget",1);Store.put(Store.settings(state),"new_limit",1);Store.put(secret,"private",true);Store.put(paused,"status","paused");
        Store.items(state).put(first).put(second).put(secret).put(paused);store.save(state);
        DeliveryEngine.Gateway gateway=new DeliveryEngine.Gateway(){public boolean allowed(){return true;}public int id(int target){return 72;}public int apply(Bitmap image,int target){return 72;}};
        DeliveryEngine engine=new DeliveryEngine(c,gateway);assertTrue(engine.apply(first.optString("id"),false,at(3,12)).contains("已应用"));
        String result=engine.apply(null,false,at(3,13));assertTrue(result,result.contains("已应用"));
        state=store.load();assertEquals(second.optString("id"),Store.session(state).optString("current_id"));assertEquals(0,Store.find(state,second.optString("id")).optInt("stage"));assertEquals(2,Store.session(state).optInt("new_count"));
        assertTrue(ReviewPlanner.candidates(state,at(3,16),zone).isEmpty());
        assertTrue(engine.apply(null,false,at(3,14)).contains("已应用"));state=store.load();assertEquals(first.optString("id"),Store.session(state).optString("current_id"));assertEquals(0,Store.find(state,first.optString("id")).optInt("stage"));
        assertEquals(-1,Store.find(state,secret.optString("id")).optInt("stage"));assertEquals(-1,Store.find(state,paused.optString("id")).optInt("stage"));
    }
    @Test public void manualNextExplainsWhenOnlyCurrentEntryIsEligible()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),first=entry(),secret=entry();Store.put(secret,"private",true);Store.items(state).put(first).put(secret);store.save(state);
        final int[] calls={0};DeliveryEngine.Gateway gateway=new DeliveryEngine.Gateway(){public boolean allowed(){return true;}public int id(int target){return 73;}public int apply(Bitmap image,int target){calls[0]++;return 73;}};
        DeliveryEngine engine=new DeliveryEngine(c,gateway);engine.apply(first.optString("id"),false,at(3,12));
        String result=engine.apply(null,false,at(3,13));assertTrue(result,result.contains("只有一条可展示"));assertEquals(1,calls[0]);assertEquals(first.optString("id"),Store.session(store.load()).optString("current_id"));
    }
    @Test public void privateDataBackupAndDuplicateRestore()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),e=entry(),secret=entry();Store.put(secret,"private",true);Store.items(state).put(e);Store.items(state).put(secret);store.save(state);
        assertEquals(state.toString(),store.load().toString());
        ByteArrayOutputStream out=new ByteArrayOutputStream();Backup.export(c,out,false);Backup.ImportPlan plan=Backup.inspect(c,new ByteArrayInputStream(out.toByteArray()));assertEquals("added="+plan.added+" conflicts="+plan.conflicts+" data="+plan.data,1,plan.duplicates);assertEquals(0,plan.added);
        Backup.restore(c,plan,0);assertEquals(2,Store.items(store.load()).length());assertFalse(Store.settings(store.load()).optBoolean("enabled"));
    }
    @Test public void zipTraversalRejected()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(bytes)){zip.putNextEntry(new ZipEntry("../escape.json"));zip.write(1);zip.closeEntry();}
        try{Backup.inspect(RuntimeEnvironment.getApplication(),new ByteArrayInputStream(bytes.toByteArray()));fail("unsafe path accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("路径"));}
    }
    @Test public void unicodeAndOverflowAreNeverTruncated()throws Exception {
        assertEquals(1,WallpaperComposer.graphemes("👨‍👩‍👧‍👦"));
        JSONObject e=entry();Store.put(e,"summary",String.join("",Collections.nCopies(121,"中")));
        try{WallpaperComposer.validate(e);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("120"));}
        Store.put(e,"summary",String.join("",Collections.nCopies(120,"中")));
        try{WallpaperComposer.render(RuntimeEnvironment.getApplication(),e,Store.settings(Store.fresh()),540,1200,2);fail("should reject unsafe rendering");}catch(IOException expected){assertTrue(expected.getMessage().contains("5 行"));}
    }
    @Test public void android15ActivityRecreationPreservesDraft()throws Exception {
        org.robolectric.android.controller.ActivityController<MainActivity> controller=org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity=controller.get();activity.findViewById(MainActivity.ID_NEW).performClick();
        ((android.widget.EditText)activity.findViewById(MainActivity.ID_SUMMARY)).setText("把问题写清楚，再开始行动。");
        controller.recreate();MainActivity recreated=controller.get();
        assertEquals("把问题写清楚，再开始行动。",((android.widget.EditText)recreated.findViewById(MainActivity.ID_SUMMARY)).getText().toString());
        controller.pause().stop().destroy();
    }
    @Test public void bundledArtworksDecodeAndRenderThreeDistinctWallpapers()throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject cfg=Store.settings(Store.fresh()),e=entry();Set<Long> fingerprints=new HashSet<>();
        for(int theme=6;theme<=8;theme++) {
            Bitmap artwork=WallpaperComposer.builtInImage(c,theme,360,800);assertNotNull(artwork);assertTrue(artwork.getHeight()>artwork.getWidth());assertTrue(artwork.getWidth()>=360);artwork.recycle();
            Store.put(cfg,"theme",theme);WallpaperComposer.Rendered composed=WallpaperComposer.render(c,e,cfg,360,800,2);assertFalse(composed.photoFallback);assertEquals(360,composed.bitmap.getWidth());assertEquals(800,composed.bitmap.getHeight());
            java.util.zip.CRC32 fingerprint=new java.util.zip.CRC32();int[] pixels=new int[360*800];composed.bitmap.getPixels(pixels,0,360,0,0,360,800);for(int pixel:pixels){fingerprint.update(pixel>>>24);fingerprint.update(pixel>>>16);fingerprint.update(pixel>>>8);fingerprint.update(pixel);}fingerprints.add(fingerprint.getValue());
            // These points are above the experience text region: a solid fallback must not pass.
            Set<Integer> sky=new HashSet<>();for(int y=0;y<200;y+=17)for(int x=0;x<360;x+=19)sky.add(composed.bitmap.getPixel(x,y));assertTrue("Missing illustrated background for "+theme,sky.size()>30);composed.bitmap.recycle();
        }
        assertEquals("Each bundled artwork must remain a distinct usable wallpaper",3,fingerprints.size());
    }
    @Test public void interfaceStyleControlsHintsAndSurvivesRecreation()throws Exception {
        Context c=RuntimeEnvironment.getApplication();c.getSharedPreferences(MainActivity.class.getSimpleName(),0).edit().clear().commit();
        org.robolectric.android.controller.ActivityController<MainActivity> controller=org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();MainActivity activity=controller.get();
        assertEquals("minimal",Store.settings(new Store(c).load()).optString("ui_style"));assertNull(findText(activity.getWindow().getDecorView(),"让经验，在日常里重逢"));
        findText(activity.getWindow().getDecorView(),"设置").performClick();activity.findViewById(302).performClick();assertEquals("guided",Store.settings(new Store(c).load()).optString("ui_style"));assertNotNull(findText(activity.getWindow().getDecorView(),"让经验，在日常里重逢"));
        controller.recreate();activity=controller.get();assertTrue(((android.widget.RadioButton)activity.findViewById(302)).isChecked());assertNotNull(findText(activity.getWindow().getDecorView(),"让经验，在日常里重逢"));
        activity.findViewById(301).performClick();assertEquals("minimal",Store.settings(new Store(c).load()).optString("ui_style"));assertNull(findText(activity.getWindow().getDecorView(),"让经验，在日常里重逢"));controller.pause().stop().destroy();
    }
    private android.view.View findText(android.view.View view,String label){if(view instanceof android.widget.TextView&&label.equals(((android.widget.TextView)view).getText().toString()))return view;if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){android.view.View match=findText(group.getChildAt(i),label);if(match!=null)return match;}}return null;}
    @Test public void completeLocalSnapshotRestoresPrivateExperiencesSettingsAndPhotos()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),regular=entry(),secret=entry();Store.put(secret,"private",true);Store.put(secret,"body","这段经历只在应用内保存。");Store.put(regular,"stage",2);Store.put(regular,"due",LocalDate.of(2026,11,1).toEpochDay());
        String photo="photos/"+UUID.randomUUID()+".jpg";File photoFile=store.photoFile(photo);photoFile.getParentFile().mkdirs();Bitmap image=Bitmap.createBitmap(12,20,Bitmap.Config.ARGB_8888);image.eraseColor(0xFF768B7A);try(OutputStream out=new FileOutputStream(photoFile)){assertTrue(image.compress(Bitmap.CompressFormat.JPEG,90,out));}image.recycle();byte[] originalPhoto=java.nio.file.Files.readAllBytes(photoFile.toPath());
        Store.put(regular,"photo",photo);Store.items(state).put(regular).put(secret);Store.put(Store.settings(state),"photo",photo);Store.put(Store.settings(state),"ui_style","guided");Store.put(Store.settings(state),"theme",8);store.save(state);
        ByteArrayOutputStream archive=new ByteArrayOutputStream();Backup.export(c,archive,true);store.save(Store.fresh());assertTrue(photoFile.delete());Backup.ImportPlan plan=Backup.inspect(c,new ByteArrayInputStream(archive.toByteArray()));assertEquals(2,plan.added);Backup.restore(c,plan,0);
        JSONObject restored=store.load();assertEquals(2,Store.items(restored).length());assertTrue(Store.find(restored,secret.optString("id")).optBoolean("private"));assertEquals(secret.optString("body"),Store.find(restored,secret.optString("id")).optString("body"));assertEquals(2,Store.find(restored,regular.optString("id")).optInt("stage"));assertEquals(regular.optLong("due"),Store.find(restored,regular.optString("id")).optLong("due"));assertEquals("guided",Store.settings(restored).optString("ui_style"));assertEquals(8,Store.settings(restored).optInt("theme"));assertFalse(Store.settings(restored).optBoolean("enabled"));assertArrayEquals(originalPhoto,java.nio.file.Files.readAllBytes(store.photoFile(Store.settings(restored).optString("photo")).toPath()));
    }
    @Test public void localFolderRejectsCloudRestrictedPathsAndReadOnlyGrant()throws Exception {
        Context c=RuntimeEnvironment.getApplication();LocalBackup.preferences(c).edit().clear().commit();
        String[] trees={"content://example.cloud.documents/tree/primary%3ADocuments%2FWengu","content://com.android.externalstorage.documents/tree/primary%3A","content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fcom.wengu.app","content://com.android.externalstorage.documents/tree/primary%3ADownload"};
        for(String tree:trees){try{LocalBackup.configure(c,android.net.Uri.parse(tree),3);fail("Unsafe destination accepted: "+tree);}catch(IOException expected){assertFalse(LocalBackup.configured(c));}}
        try{LocalBackup.configure(c,android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWengu"),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);fail("Read-only destination accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("读写"));assertFalse(LocalBackup.configured(c));}
    }
    @Test public void manualRotationDoesNotStarveOrdinaryFutureDueExperience()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),first=entry(),second=entry(),ordinary=entry();Store.put(first,"important",true);Store.put(second,"important",true);Store.put(Store.settings(state),"theme",0);Store.put(Store.settings(state),"budget",1);Store.put(Store.settings(state),"new_limit",0);
        long due=LocalDate.of(2026,10,10).toEpochDay(),lastReview=at(1,8);for(JSONObject item:new JSONObject[]{first,second,ordinary}){Store.put(item,"stage",2);Store.put(item,"due",due);Store.put(item,"last_success",lastReview);Store.items(state).put(item);}store.save(state);
        DeliveryEngine.Gateway gateway=new DeliveryEngine.Gateway(){public boolean allowed(){return true;}public int id(int target){return 79;}public int apply(Bitmap image,int target){return 79;}};DeliveryEngine engine=new DeliveryEngine(c,gateway);assertTrue(engine.apply(first.optString("id"),false,at(3,8)).contains("已应用"));
        String[] rotation={second.optString("id"),ordinary.optString("id"),first.optString("id"),second.optString("id"),ordinary.optString("id"),first.optString("id")};
        for(int i=0;i<rotation.length;i++){assertTrue(engine.apply(null,false,at(3,9)+i*60_000L).contains("已应用"));state=store.load();assertEquals("Unimportant experience must not be starved by two important ones",rotation[i],Store.session(state).optString("current_id"));}
        for(JSONObject original:new JSONObject[]{first,second,ordinary}){JSONObject repeated=Store.find(state,original.optString("id"));assertEquals("An early manual repeat must preserve review stage",2,repeated.optInt("stage"));assertEquals(due,repeated.optLong("due"));assertEquals(lastReview,repeated.optLong("last_success"));assertTrue(repeated.optLong("last_displayed")>=at(3,9));}assertEquals(0,Store.session(state).optInt("new_count"));
    }
    @Test public void manualOrderingUsesLastDisplayAndLegacyReviewTimestampBeforeImportance() {
        JSONObject state=Store.fresh(),older=entry(),important=entry(),latest=entry(),current=entry(),secret=entry(),paused=entry(),archived=entry();long today=ReviewPlanner.day(at(3,12),zone);
        Store.put(older,"last_success",at(1,8));Store.put(older,"stage",2);Store.put(older,"due",today+30); // Legacy item without last_displayed.
        Store.put(important,"important",true);Store.put(important,"last_success",at(1,8));Store.put(important,"last_displayed",at(2,8));Store.put(important,"stage",2);Store.put(important,"due",today-1);
        Store.put(latest,"last_displayed",at(2,12));Store.put(current,"last_displayed",0);Store.put(secret,"private",true);Store.put(paused,"status","paused");Store.put(archived,"status","archived");Store.put(Store.session(state),"current_id",current.optString("id"));for(JSONObject item:new JSONObject[]{important,latest,secret,current,older,paused,archived})Store.items(state).put(item);
        List<JSONObject> choices=ReviewPlanner.manualCandidates(state,at(3,12),zone);assertEquals(3,choices.size());assertEquals(older.optString("id"),choices.get(0).optString("id"));assertEquals(important.optString("id"),choices.get(1).optString("id"));assertTrue(ReviewPlanner.readyForReview(state,important,at(3,12),zone));assertFalse(ReviewPlanner.readyForReview(state,older,at(3,12),zone));
        Store.put(important,"last_displayed",at(1,8));choices=ReviewPlanner.manualCandidates(state,at(3,12),zone);assertEquals("Importance only breaks the equal-display-time tie",important.optString("id"),choices.get(0).optString("id"));
    }
    @Test public void automaticOverduePriorityDoesNotUseManualFairRotationOrder() {
        JSONObject state=Store.fresh(),oldest=entry(),tiedOrdinary=entry(),tiedImportant=entry(),fresh=entry();long today=ReviewPlanner.day(at(3,12),zone);
        for(JSONObject item:new JSONObject[]{oldest,tiedOrdinary,tiedImportant}){Store.put(item,"stage",2);Store.put(item,"last_success",at(1,8));Store.put(item,"due",today-2);Store.items(state).put(item);}Store.put(oldest,"due",today-3);Store.put(oldest,"last_displayed",at(3,11));Store.put(tiedOrdinary,"last_displayed",at(1,8));Store.put(tiedImportant,"important",true);Store.put(tiedImportant,"last_displayed",at(3,10));Store.items(state).put(fresh);
        List<JSONObject> choices=ReviewPlanner.candidates(state,at(3,12),zone);assertEquals(3,choices.size());assertEquals(oldest.optString("id"),choices.get(0).optString("id"));assertEquals(tiedImportant.optString("id"),choices.get(1).optString("id"));assertEquals(tiedOrdinary.optString("id"),choices.get(2).optString("id"));
    }
    @Test public void reapplyingCurrentTargetPreservesFutureAndOverdueReviewAndPause()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);
        for(int dueDay:new int[]{2,10}) {
            JSONObject state=Store.fresh(),item=entry();Store.put(Store.settings(state),"theme",0);Store.put(item,"stage",2);Store.put(item,"due",LocalDate.of(2026,10,dueDay).toEpochDay());Store.put(item,"last_success",at(1,8));Store.items(state).put(item);store.save(state);
            class Fake implements DeliveryEngine.Gateway {int home=50,lock=70;public boolean allowed(){return true;}public int id(int target){return target==1?home:lock;}public int apply(Bitmap image,int target){return target==1?++home:++lock;}}
            Fake gateway=new Fake();DeliveryEngine engine=new DeliveryEngine(c,gateway);assertTrue(engine.apply(item.optString("id"),false,at(1,10)).contains("已应用"));
            state=store.load();JSONObject original=Store.copy(Store.find(state,item.optString("id")));Store.put(Store.settings(state),"enabled",false);Store.put(Store.settings(state),"target",1);store.save(state);int originalLock=gateway.lock,originalHome=gateway.home;
            assertTrue(engine.applyCurrentSettings(at(3,12)).contains("已应用到桌面"));state=store.load();JSONObject current=Store.find(state,item.optString("id"));assertEquals(original.optInt("stage"),current.optInt("stage"));assertEquals(original.optLong("due"),current.optLong("due"));assertEquals(original.optLong("last_success"),current.optLong("last_success"));assertEquals(original.optLong("last_displayed"),current.optLong("last_displayed"));assertFalse(Store.settings(state).optBoolean("enabled"));assertEquals(1,Store.session(state).optInt("current_targets"));assertEquals(item.optString("id"),Store.session(state).optString("current_id"));assertEquals(originalLock,gateway.lock);assertEquals(originalHome+1,gateway.home);
        }
    }
    @Test public void unrelatedWallpaperChangesDoNotPauseCurrentLockTarget()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);JSONObject state=Store.fresh(),item=entry();Store.put(Store.settings(state),"theme",0);Store.put(Store.settings(state),"target",3);Store.put(item,"stage",2);Store.put(item,"due",LocalDate.of(2026,10,20).toEpochDay());Store.items(state).put(item);store.save(state);
        class Fake implements DeliveryEngine.Gateway {int home=80,lock=90;public boolean allowed(){return true;}public int id(int target){return target==1?home:lock;}public int apply(Bitmap image,int target){return target==1?++home:++lock;}}
        Fake gateway=new Fake();DeliveryEngine engine=new DeliveryEngine(c,gateway);engine.apply(item.optString("id"),false,at(3,8));state=store.load();Store.put(Store.settings(state),"target",2);store.save(state);engine.applyCurrentSettings(at(3,9));gateway.home+=100;
        assertTrue(engine.apply(null,true,at(3,10)).contains("至少两小时"));assertTrue(Store.settings(store.load()).optBoolean("enabled"));gateway.lock+=100;assertTrue(engine.apply(null,true,at(3,10)).contains("壁纸已改变"));assertFalse(Store.settings(store.load()).optBoolean("enabled"));
    }
    @Test public void backupRevisionChangesOnlyForExportedPayloadAndLegacyStatusStaysUnconfirmed()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);LocalBackup.preferences(c).edit().clear().commit();LocalBackup.verifyAvailable(c);assertFalse("An installation without a backup folder must not manufacture a failure",LocalBackup.preferences(c).contains("error"));JSONObject state=store.load();long revision=Store.revision(state);String epoch=Store.epoch(state);assertEquals("未设置本地备份",LocalBackup.summary(c));assertFalse(LocalBackup.coverageCurrent(c));
        Store.put(Store.session(state),"status","只是更新运行状态");Store.log(state,"attempt","","仅日志变化");store.save(state);assertEquals(revision,Store.revision(store.load()));assertEquals(epoch,Store.epoch(store.load()));
        JSONObject item=entry();state=store.load();Store.items(state).put(item);store.save(state);assertEquals(++revision,Store.revision(store.load()));store.save(store.load());assertEquals(revision,Store.revision(store.load()));state=store.load();Store.put(Store.settings(state),"target",1);store.save(state);assertEquals(++revision,Store.revision(store.load()));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();Backup.export(c,bytes,true);Backup.ImportPlan snapshot=Backup.inspect(c,new ByteArrayInputStream(bytes.toByteArray()));assertFalse(snapshot.data.has("_backup_epoch"));assertFalse(snapshot.data.has("_backup_revision"));snapshot.discard();
        android.content.SharedPreferences p=LocalBackup.preferences(c);p.edit().putString("tree","content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FWQA").putBoolean("enabled",false).putLong("last",at(3,8)).putString("digest","legacy-only").commit();assertEquals("备份已暂停",LocalBackup.summary(c));assertFalse(LocalBackup.coverageCurrent(c));state=store.load();Store.put(Store.find(state,item.optString("id")),"body","暂停期间新写的经历");store.save(state);assertTrue(LocalBackup.afterSave(c,"经验已保存").contains("本次内容尚未备份"));
        p.edit().putBoolean("enabled",true).commit();assertEquals("有未备份内容",LocalBackup.summary(c));assertFalse("An old timestamp/digest alone must not claim current coverage",LocalBackup.coverageCurrent(c));p.edit().putString("error","文件夹权限失效").commit();assertEquals("备份失败",LocalBackup.summary(c));p.edit().putBoolean("awaiting_restore",true).commit();assertEquals("待恢复历史备份",LocalBackup.summary(c));
    }
    @Test public void previewClockAndNotificationNeverModifyWallpaperBitmap() {
        Context c=RuntimeEnvironment.getApplication();Bitmap source=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);source.eraseColor(0xFF284B3F);Bitmap original=source.copy(Bitmap.Config.ARGB_8888,false);LockScreenPreview preview=new LockScreenPreview(c);preview.setPreview(source,2,"留一点安静的时间。");preview.measure(android.view.View.MeasureSpec.makeMeasureSpec(360,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(800,android.view.View.MeasureSpec.EXACTLY));preview.layout(0,0,360,800);
        Bitmap lock=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);preview.draw(new android.graphics.Canvas(lock));assertTrue(source.sameAs(original));assertFalse("Clock/notification must appear only on the composed preview view",source.sameAs(lock));assertTrue(preview.getContentDescription().toString().contains("不会写入壁纸"));
        preview.setPreview(source,1,"留一点安静的时间。");Bitmap home=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);preview.draw(new android.graphics.Canvas(home));assertTrue("Home preview must not include lock-screen placeholder decorations",source.sameAs(home));assertTrue(source.sameAs(original));source.recycle();original.recycle();lock.recycle();home.recycle();
    }
    @Test public void appearanceOnlyPartialDeliveryKeepsItsMeaningAcrossNewEngineRetries()throws Exception {
        Context c=RuntimeEnvironment.getApplication();Store store=new Store(c);
        for(boolean retryAutomatically:new boolean[]{false,true}) {
            JSONObject state=Store.fresh(),item=entry();Store.put(Store.settings(state),"theme",0);Store.put(item,"stage",2);Store.put(item,"due",LocalDate.of(2026,10,2).toEpochDay());Store.put(item,"last_success",at(1,8));Store.items(state).put(item);store.save(state);
            class Fake implements DeliveryEngine.Gateway {int home=150,lock=170,homeCalls,lockCalls;boolean failHomeOnce=true;public boolean allowed(){return true;}public int id(int target){return target==1?home:lock;}public int apply(Bitmap image,int target)throws Exception{if(target==2){lockCalls++;return ++lock;}homeCalls++;if(failHomeOnce){failHomeOnce=false;throw new IOException("one temporary desktop failure");}return ++home;}}
            Fake gateway=new Fake();DeliveryEngine firstEngine=new DeliveryEngine(c,gateway);assertTrue(firstEngine.apply(item.optString("id"),false,at(1,10)).contains("已应用"));state=store.load();JSONObject original=Store.copy(Store.find(state,item.optString("id")));long previousSessionSuccess=Store.session(state).optLong("last_success");Store.put(Store.settings(state),"target",3);Store.put(Store.settings(state),"enabled",retryAutomatically);store.save(state);
            long attemptTime=at(3,12);assertTrue(firstEngine.applyCurrentSettings(attemptTime).contains("部分成功"));state=store.load();JSONObject pending=Store.session(state).optJSONObject("pending");assertNotNull(pending);assertEquals(2,pending.optInt("done"));assertTrue("The durable attempt must retain appearance-only semantics",pending.optBoolean("appearance_only"));assertFalse(pending.optBoolean("advance"));assertEquals(retryAutomatically,Store.settings(state).optBoolean("enabled"));assertEquals(previousSessionSuccess,Store.session(state).optLong("last_success"));String pendingExperienceId=pending.optString("experience_id");
            // A new engine must learn the attempt's purpose from disk, including through
            // the generic manual/automatic entry points rather than applyCurrentSettings.
            DeliveryEngine recreatedEngine=new DeliveryEngine(c,gateway);long retryTime=attemptTime+1_000;String result=recreatedEngine.apply(retryAutomatically?null:pendingExperienceId,retryAutomatically,retryTime);assertTrue(result,result.contains("已应用"));state=store.load();JSONObject repeated=Store.find(state,item.optString("id"));assertNull(Store.session(state).optJSONObject("pending"));assertEquals(2,gateway.lockCalls);assertEquals(2,gateway.homeCalls);assertEquals(retryAutomatically,Store.settings(state).optBoolean("enabled"));assertEquals(3,Store.session(state).optInt("current_targets"));assertEquals(retryTime,Store.session(state).optLong("last_success"));assertEquals(original.optInt("stage"),repeated.optInt("stage"));assertEquals(original.optLong("due"),repeated.optLong("due"));assertEquals(original.optLong("last_success"),repeated.optLong("last_success"));assertEquals(original.optLong("last_displayed"),repeated.optLong("last_displayed"));
        }
    }
}
