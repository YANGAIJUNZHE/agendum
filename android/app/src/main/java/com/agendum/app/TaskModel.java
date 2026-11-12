package com.agendum.app;

import org.json.*;
import java.util.*;

/** 纯日程模型：同一份字段白名单用于编辑、队列和冲突处理。 */
public final class TaskModel {
    public static final String[] FIELDS = {"summary", "notes", "deadline", "category", "status", "pinned", "urgent_override", "snooze_until"};
    public static JSONObject emptyTime() throws JSONException { return new JSONObject().put("kind", "none").put("value", ""); }
    public static JSONObject defaults(String uuid) throws JSONException {
        return new JSONObject().put("uuid", uuid).put("version", 0).put("summary", "").put("notes", "")
            .put("deadline", emptyTime()).put("category", "action").put("status", "open").put("pinned", false)
            .put("urgent_override", JSONObject.NULL).put("snooze_until", emptyTime()).put("deleted", false);
    }
    public static JSONObject copy(JSONObject object) throws JSONException { return new JSONObject(object.toString()); }
    public static JSONObject editable(JSONObject object) throws JSONException {
        JSONObject out = new JSONObject();
        for (String field : FIELDS) out.put(field, object.opt(field));
        return out;
    }
    public static void patch(JSONObject task, JSONObject fields) throws JSONException {
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!Arrays.asList(FIELDS).contains(key)) throw new JSONException("不能修改字段：" + key);
            task.put(key, fields.get(key));
        }
    }
    public static void validate(JSONObject task) throws JSONException {
        Object summary = task.opt("summary"), notes = task.opt("notes");
        if (!(summary instanceof String) || ((String)summary).trim().isEmpty() || ((String)summary).length() > 200) throw new JSONException("请填写 1 到 200 字的待办内容");
        if (!(notes instanceof String) || ((String)notes).length() > 10000) throw new JSONException("备注请控制在 10000 字以内");
        if (!Arrays.asList("candidate", "open", "done", "dismissed", "expired").contains(task.optString("status"))) throw new JSONException("无效待办状态");
        if (!Arrays.asList("urgent", "action", "academic", "info").contains(task.optString("category"))) throw new JSONException("无效分类");
        if (!(task.opt("pinned") instanceof Boolean) || !(task.isNull("urgent_override") || task.opt("urgent_override") instanceof Boolean)) throw new JSONException("无效标记");
        validateTime(task.getJSONObject("deadline"), true);
        validateTime(task.getJSONObject("snooze_until"), false);
        boolean finished = Arrays.asList("done", "dismissed", "expired").contains(task.optString("status"));
        if (finished && (task.optBoolean("pinned") || !"none".equals(task.getJSONObject("snooze_until").optString("kind")))) throw new JSONException("完成前请取消置顶和稍后提醒");
    }
    private static void validateTime(JSONObject time, boolean dateAllowed) throws JSONException {
        String kind = time.optString("kind"), value = time.optString("value");
        if ("none".equals(kind) && value.isEmpty()) return;
        if (dateAllowed && "date".equals(kind) && value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US); f.setLenient(false);
            try { f.parse(value); return; } catch (Exception ignored) { }
        }
        if ("datetime".equals(kind) && value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})")) {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US); f.setLenient(false);
            try { f.parse(value); return; } catch (Exception ignored) { }
        }
        throw new JSONException("请选择有效日期或具体时刻");
    }
}
