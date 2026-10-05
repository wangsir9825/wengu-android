package com.wengu.app;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.*;
import java.util.*;

/** Exposure scheduling only. No display event is interpreted as a memory rating. */
public final class ReviewPlanner {
    public static final int[] INTERVALS={1,3,7,14,30,60};
    public static final long MIN_GAP=2*60*60*1000L;
    public static final long MIN_STAGE_GAP=20*60*60*1000L;
    public static long day(long now, ZoneId zone) { return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay(); }
    public static List<JSONObject> candidates(JSONObject state,long now,ZoneId zone) {
        long today=day(now,zone); JSONObject s=Store.session(state), cfg=Store.settings(state);
        if(s.optLong("day",-1)!=today) {
            Store.put(s,"day",today); Store.put(s,"delivered",new JSONArray()); Store.put(s,"shown",new JSONArray()); Store.put(s,"new_count",0); Store.put(s,"last_slot",-1);
        }
        Set<String> done=new HashSet<>(); JSONArray delivered=s.optJSONArray("delivered");
        if(delivered!=null) for(int i=0;i<delivered.length();i++)done.add(delivered.optString(i));
        JSONArray shown=s.optJSONArray("shown");
        int room=Math.max(0,Math.max(1,Math.min(8,cfg.optInt("budget",4)))-(shown==null?0:shown.length()));
        List<JSONObject> due=new ArrayList<>(), fresh=new ArrayList<>();
        JSONArray items=Store.items(state);
        for(int i=0;i<items.length();i++) {
            JSONObject e=items.optJSONObject(i);
            if(!Store.eligible(e) || done.contains(e.optString("id")))continue;
            if(e.optLong("last_success")>0 && now-e.optLong("last_success")<MIN_STAGE_GAP)continue;
            if(e.optInt("stage",-1)<0)fresh.add(e);
            else if(e.optLong("due")<=today)due.add(e);
        }
        Comparator<JSONObject> order=Comparator.comparingLong((JSONObject e)->e.optLong("due"))
                .thenComparing(e->!e.optBoolean("important"))
                .thenComparingLong(e->e.optLong("last_success"))
                .thenComparing(e->e.optString("id"));
        due.sort(order); fresh.sort(order);
        List<JSONObject> result=new ArrayList<>();
        for(JSONObject e:due) { if(result.size()>=room)break;result.add(e); }
        // New experiences do not displace overdue experiences.
        if(due.isEmpty()) {
            int newRoom=Math.max(0,cfg.optInt("new_limit",1)-s.optInt("new_count"));
            for(JSONObject e:fresh) { if(result.size()>=room || newRoom--<=0)break;result.add(e); }
        }
        return result;
    }
    /** Manual display ignores automatic quotas, but never counts an early repeat as a review. */
    public static boolean readyForReview(JSONObject state,JSONObject item,long now,ZoneId zone) {
        if(!Store.eligible(item))return false;
        if(item.optLong("last_success")>0&&now-item.optLong("last_success")<MIN_STAGE_GAP)return false;
        JSONObject session=Store.session(state);JSONArray delivered=session.optJSONArray("delivered");
        if(session.optLong("day",-1)==day(now,zone)&&delivered!=null)for(int i=0;i<delivered.length();i++)if(item.optString("id").equals(delivered.optString(i)))return false;
        return item.optInt("stage",-1)<0||item.optLong("due")<=day(now,zone);
    }
    public static List<JSONObject> manualCandidates(JSONObject state,long now,ZoneId zone) {
        String current=Store.session(state).optString("current_id");List<JSONObject> result=new ArrayList<>();
        for(int i=0;i<Store.items(state).length();i++){JSONObject item=Store.items(state).optJSONObject(i);if(Store.eligible(item)&&!current.equals(item.optString("id")))result.add(item);}
        // Manual switching is a fair rotation through eligible experiences. Successful
        // display time comes first; importance breaks a tie rather than starving ordinary
        // entries. readyForReview is evaluated by the delivery engine only for advancement.
        result.sort(Comparator.comparingLong((JSONObject item)->item.optLong("last_displayed",item.optLong("last_success")))
                .thenComparing(item->!item.optBoolean("important"))
                .thenComparing(item->item.optString("id")));
        return result;
    }
    public static String manualEmptyReason(JSONObject state) {
        int count=0;for(int i=0;i<Store.items(state).length();i++)if(Store.eligible(Store.items(state).optJSONObject(i)))count++;
        return count==1?"只有一条可展示的经验，保留当前壁纸；请再添加一条，或恢复其他经验参与展示":"没有可展示的经验；请添加一条，或恢复未暂停、未归档的非私密经验";
    }
    public static boolean active(JSONObject state,long now,ZoneId zone) {
        JSONObject cfg=Store.settings(state); int hour=Instant.ofEpochMilli(now).atZone(zone).getHour();
        return hour>=cfg.optInt("start_hour",8) && hour<cfg.optInt("end_hour",22);
    }
    public static int slot(JSONObject state,long now,ZoneId zone) {
        JSONObject cfg=Store.settings(state);
        int start=cfg.optInt("start_hour",8),end=cfg.optInt("end_hour",22),budget=cfg.optInt("budget",4);
        // Two-hour dwell time is preserved even if a short active window cannot fit all slots.
        int count=Math.max(1,Math.min(budget,(end-start+1)/2));
        double step=count==1?0:Math.max(2,Math.floor((end-start-1.0)/(count-1)));
        double hour=Instant.ofEpochMilli(now).atZone(zone).getHour()+Instant.ofEpochMilli(now).atZone(zone).getMinute()/60.0;
        if(hour<start||hour>=end)return -1;
        return count==1?0:Math.min(count-1,(int)Math.floor((hour-start)/step));
    }
    public static long nextAttempt(JSONObject state,long now,ZoneId zone) {
        if(!Store.settings(state).optBoolean("enabled"))return 0;
        JSONObject cfg=Store.settings(state),s=Store.session(state);
        int start=cfg.optInt("start_hour",8),end=cfg.optInt("end_hour",22),budget=cfg.optInt("budget",4);
        int count=Math.max(1,Math.min(budget,(end-start+1)/2));
        int step=count==1?0:Math.max(2,(int)Math.floor((end-start-1.0)/(count-1)));
        LocalDate date=Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long earliest=Math.max(now+1,s.optLong("last_any")+MIN_GAP);
        for(int d=0;d<3;d++)for(int i=0;i<count;i++) {
            long t=date.plusDays(d).atTime(start+i*step,0).atZone(zone).toInstant().toEpochMilli();
            if(t>=earliest)return t;
        }
        return date.plusDays(3).atTime(start,0).atZone(zone).toInstant().toEpochMilli();
    }
    public static void complete(JSONObject state,JSONObject attempt,long now,ZoneId zone) {
        if(attempt.optBoolean("committed"))return;
        JSONObject e=Store.find(state,attempt.optString("experience_id"));
        if(e!=null && attempt.optBoolean("advance") && e.optInt("stage",-1)==attempt.optInt("expected_stage",-1)) {
            int old=e.optInt("stage",-1),stage=Math.min(5,old+1);
            Store.put(e,"stage",stage); Store.put(e,"due",day(now,zone)+INTERVALS[stage]);
            Store.put(e,"last_success",now); Store.put(e,"modified",now);
            candidates(state,now,zone);
            JSONObject s=Store.session(state); JSONArray delivered=s.optJSONArray("delivered");
            boolean exists=false; for(int i=0;i<delivered.length();i++)if(e.optString("id").equals(delivered.optString(i)))exists=true;
            if(!exists)delivered.put(e.optString("id"));
            if(old<0 && !exists)Store.put(s,"new_count",s.optInt("new_count")+1);
        }
        Store.put(attempt,"committed",true);
    }
}
