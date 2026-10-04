package com.moments.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.*;

public class Store {
    private static final String P = "moments_store";
    static SharedPreferences sp(Context c){ return c.getSharedPreferences(P, Context.MODE_PRIVATE); }
    public static void saveCapture(Context c, String text){
        if(text==null || text.trim().isEmpty()) return;
        sp(c).edit().putString("latest", text).apply();
        Set<String> old = new LinkedHashSet<>(sp(c).getStringSet("history", new LinkedHashSet<>()));
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        old.add(ts + "\n" + text);
        while(old.size()>50){ Iterator<String> it=old.iterator(); if(it.hasNext()){it.next(); it.remove();} }
        sp(c).edit().putStringSet("history", old).apply();
    }
    public static String latest(Context c){ return sp(c).getString("latest", ""); }
    public static ArrayList<String> history(Context c){ ArrayList<String> a = new ArrayList<>(sp(c).getStringSet("history", new LinkedHashSet<>())); Collections.reverse(a); return a; }
    public static void addMaterial(Context c, String path){ Set<String> s = new LinkedHashSet<>(sp(c).getStringSet("materials", new LinkedHashSet<>())); s.add(path); sp(c).edit().putStringSet("materials", s).apply(); }
    public static ArrayList<String> materials(Context c){ ArrayList<String> a=new ArrayList<>(sp(c).getStringSet("materials", new LinkedHashSet<>())); Collections.reverse(a); return a; }
}
