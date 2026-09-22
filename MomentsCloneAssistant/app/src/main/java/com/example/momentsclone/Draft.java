package com.example.momentsclone;

import org.json.JSONException;
import org.json.JSONObject;

public class Draft {
    public final String id;
    public final long createdAt;
    public final String text;
    public final String screenshotUri;

    public Draft(String id, long createdAt, String text, String screenshotUri) {
        this.id = id;
        this.createdAt = createdAt;
        this.text = text == null ? "" : text;
        this.screenshotUri = screenshotUri == null ? "" : screenshotUri;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("createdAt", createdAt);
        o.put("text", text);
        o.put("screenshotUri", screenshotUri);
        return o;
    }

    public static Draft fromJson(JSONObject o) {
        return new Draft(
                o.optString("id"),
                o.optLong("createdAt"),
                o.optString("text"),
                o.optString("screenshotUri")
        );
    }
}
