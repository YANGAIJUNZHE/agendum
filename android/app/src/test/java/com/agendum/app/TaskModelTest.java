package com.agendum.app;

import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskModelTest {
    @Test public void preservesDateOnlyAndOffsetDatetime() throws Exception {
        JSONObject task = TaskModel.defaults("id"); task.put("summary", "交报告");
        task.put("deadline", new JSONObject().put("kind", "date").put("value", "2026-11-02"));
        TaskModel.validate(task); assertEquals("2026-11-02", task.getJSONObject("deadline").getString("value"));
        task.put("deadline", new JSONObject().put("kind", "datetime").put("value", "2026-11-02T15:00:00+08:00"));
        TaskModel.validate(task); assertEquals("2026-11-02T15:00:00+08:00", task.getJSONObject("deadline").getString("value"));
    }
    @Test public void completionRejectsPinnedOrSnoozedState() throws Exception {
        JSONObject task = TaskModel.defaults("id"); task.put("summary", "待办").put("status", "done").put("pinned", true);
        try { TaskModel.validate(task); fail("已完成不能置顶"); } catch (JSONException expected) { assertTrue(expected.getMessage().contains("完成")); }
        task.put("pinned", false); task.put("snooze_until", new JSONObject().put("kind", "datetime").put("value", "2026-11-02T15:00:00+08:00"));
        try { TaskModel.validate(task); fail("已完成不能稍后提醒"); } catch (JSONException expected) { assertTrue(expected.getMessage().contains("完成")); }
    }
    @Test public void rejectsInvalidDateAndTimezoneLessTime() throws Exception {
        JSONObject task = TaskModel.defaults("id"); task.put("summary", "待办");
        for (String value : new String[]{"2026-02-30", "2026-13-01"}) {
            task.put("deadline", new JSONObject().put("kind", "date").put("value", value));
            try { TaskModel.validate(task); fail("无效日期必须拒绝"); } catch (JSONException expected) { assertTrue(expected.getMessage().contains("日期")); }
        }
        task.put("deadline", new JSONObject().put("kind", "datetime").put("value", "2026-11-02T15:00:00"));
        try { TaskModel.validate(task); fail("无时区不能入队"); } catch (JSONException expected) { assertTrue(expected.getMessage().contains("时刻")); }
    }
    @Test public void rejectsSourceModificationAndCopiesIndependently() throws Exception {
        JSONObject original = TaskModel.defaults("id"); original.put("summary", "原标题");
        JSONObject copy = TaskModel.copy(original); TaskModel.patch(copy, new JSONObject().put("summary", "新标题"));
        assertEquals("原标题", original.getString("summary"));
        assertEquals("新标题", copy.getString("summary"));
        try { TaskModel.patch(copy, new JSONObject().put("source", "伪造来源")); fail("来源只能读"); } catch (JSONException expected) { assertTrue(expected.getMessage().contains("source")); }
    }
}
