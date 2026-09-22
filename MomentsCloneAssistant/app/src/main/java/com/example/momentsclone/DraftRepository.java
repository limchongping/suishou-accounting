package com.example.momentsclone;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class DraftRepository {
    private static final String PREFS = "moments_clone_prefs";
    private static final String KEY_DRAFTS = "drafts";
    private static final String KEY_PENDING = "pending";
    private final SharedPreferences prefs;

    public DraftRepository(Context c) {
        prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Draft> all() {
        List<Draft> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_DRAFTS, "[]"));
            for (int i=0;i<a.length();i++) {
                JSONObject o=a.optJSONObject(i);
                if (o!=null) out.add(Draft.fromJson(o));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized void add(Draft d) {
        List<Draft> list=all();
        list.add(d);
        save(list);
    }

    public synchronized Draft first() {
        List<Draft> list=all();
        return list.isEmpty()?null:list.get(0);
    }

    public synchronized void remove(String id) {
        List<Draft> src=all(), dst=new ArrayList<>();
        for (Draft d:src) if (!d.id.equals(id)) dst.add(d);
        save(dst);
    }

    public synchronized void clear() {
        prefs.edit().putString(KEY_DRAFTS,"[]").remove(KEY_PENDING).apply();
    }

    public void setPendingId(String id) {
        prefs.edit().putString(KEY_PENDING,id==null?"":id).apply();
    }

    public Draft pending() {
        String id=prefs.getString(KEY_PENDING,"");
        for (Draft d:all()) if (d.id.equals(id)) return d;
        return null;
    }

    private void save(List<Draft> list) {
        JSONArray a=new JSONArray();
        for (Draft d:list) {
            try { a.put(d.toJson()); } catch (Exception ignored) {}
        }
        prefs.edit().putString(KEY_DRAFTS,a.toString()).apply();
    }
}
