package com.agendum.app;

import android.content.Context;
import org.json.*;
import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/** UI 只读本地视图：服务器确认数据 + 依次叠加的本地操作。 */
public final class ScheduleRepository {
    private static final ReentrantLock SYNC_LOCK = new ReentrantLock();
    private static volatile Runnable listener;
    final SyncDatabase db;
    final Credentials credentials;
    final SyncTransport transport;
    private String syncBase, syncToken, syncSpace;
    public ScheduleRepository(Context context) { this(SyncDatabase.get(context), new Credentials(context), new SyncTransport()); }
    public ScheduleRepository(SyncDatabase database, Credentials credentials, SyncTransport transport) {
        this.db = database; this.credentials = credentials; this.transport = transport;
    }
    public static synchronized void listen(Runnable callback) { listener = callback; }
    public static synchronized void stopListening(Runnable callback) { if (listener == callback) listener = null; }
    private static void changed() { Runnable callback = listener; if (callback != null) callback.run(); }
    private String workspace() { return credentials.server(); }

    JSONObject view(String workspace, String uuid) throws JSONException {
        SyncDatabase.Task row = db.data().task(workspace, uuid);
        JSONObject task = row == null ? TaskModel.defaults(uuid) : new JSONObject(row.json);
        List<SyncDatabase.Operation> operations = db.data().operations(workspace, uuid);
        if (!operations.isEmpty() && (row == null || task.optBoolean("deleted"))) {
            JSONObject saved = TaskModel.defaults(uuid);
            TaskModel.patch(saved, new JSONObject(operations.get(0).base));
            saved.put("version", task.optLong("version")); saved.put("deleted", task.optBoolean("deleted"));
            task = saved;
        }
        for (SyncDatabase.Operation op : operations) {
            if ("delete".equals(op.kind)) task.put("deleted", true);
            else TaskModel.patch(task, new JSONObject(op.changes));
            task.put("syncStatus", op.status.equals("conflict") || task.optString("syncStatus").equals("conflict") ? "conflict" : "pending");
        }
        return task;
    }

    public JSONObject snapshot() throws JSONException {
        String workspace = workspace();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (SyncDatabase.Task task : db.data().tasks(workspace)) ids.add(task.uuid);
        List<SyncDatabase.Operation> operations = db.data().operations(workspace);
        for (SyncDatabase.Operation operation : operations) ids.add(operation.uuid);
        JSONArray tasks = new JSONArray(), conflicts = new JSONArray();
        for (String uuid : ids) {
            JSONObject task = view(workspace, uuid);
            if (!task.optBoolean("deleted")) tasks.put(task);
        }
        for (SyncDatabase.Operation operation : operations) {
            if (operation.status.equals("conflict") || operation.status.equals("error")) {
                JSONObject conflict = new JSONObject().put("uuid", operation.uuid).put("operation_id", operation.operationId)
                    .put("local", view(workspace, operation.uuid)).put("type", operation.kind);
                if (!operation.error.isEmpty()) conflict.put("result", new JSONObject(operation.error));
                conflicts.put(conflict);
            }
        }
        SyncDatabase.State state = db.data().state(workspace);
        return new JSONObject().put("tasks", tasks).put("pending", operations.size()).put("conflicts", conflicts)
            .put("connected", !credentials.token().isEmpty()).put("server_id", workspace).put("base", credentials.base())
            .put("lastSuccess", state == null ? 0 : state.lastSuccess).put("error", state == null ? "" : state.error);
    }

    public void edit(String uuid, String kind, JSONObject input) throws Exception {
        edit(uuid, kind, input, null);
    }
    public void edit(String uuid, String kind, JSONObject input, JSONObject observed) throws Exception {
        if (workspace().isEmpty()) throw new Exception("请先连接电脑；首次配对后可离线编辑");
        if (!Arrays.asList("create", "update", "delete").contains(kind)) throw new Exception("不支持的日程操作");
        JSONObject changes = TaskModel.copy(input);
        if (Arrays.asList("done", "dismissed", "expired").contains(changes.optString("status"))) {
            changes.put("pinned", false).put("snooze_until", TaskModel.emptyTime());
        }
        final String ident = uuid == null || uuid.isEmpty() ? UUID.randomUUID().toString() : UUID.fromString(uuid).toString();
        final String space = workspace();
        final Exception[] error = {null};
        db.runInTransaction(() -> {
            try {
                JSONObject before = "create".equals(kind) ? TaskModel.defaults(ident) : (observed == null ? view(space, ident) : TaskModel.copy(observed));
                if (!ident.equals(before.optString("uuid"))) throw new Exception("日程身份不一致，请重新打开编辑");
                if (!"create".equals(kind) && before.optBoolean("deleted")) throw new Exception("这条日程已经删除，请在同步页处理本地修改");
                JSONObject after = TaskModel.copy(before); TaskModel.patch(after, changes);
                if (!"delete".equals(kind)) TaskModel.validate(after);
                SyncDatabase.Operation op = new SyncDatabase.Operation();
                op.operationId = UUID.randomUUID().toString(); op.workspace = space; op.uuid = ident; op.kind = kind;
                op.base = TaskModel.editable(before).toString(); op.baseVersion = before.optLong("version");
                op.changes = changes.toString();
                db.data().insert(op);
            } catch (Exception failure) { error[0] = failure; throw new IllegalArgumentException(failure); }
        });
        if (error[0] != null) throw error[0];
        changed();
    }

    /** 放弃针对这一条日程的全部本地编辑，不影响其他日程。 */
    public void discard(String uuid) { db.runInTransaction(() -> db.data().clearOperations(workspace(), uuid)); changed(); }
    public int pendingCount() { return db.data().operations(workspace()).size(); }
    public void discardAll() { db.runInTransaction(() -> db.data().discard(workspace())); changed(); }
    public String exportPending() throws JSONException {
        JSONArray result = new JSONArray();
        for (SyncDatabase.Operation op : db.data().operations(workspace())) {
            result.put(new JSONObject().put("uuid", op.uuid).put("type", op.kind).put("changes", new JSONObject(op.changes))
                .put("base", new JSONObject(op.base)).put("operation_id", op.operationId));
        }
        return new JSONObject().put("format", "agendum-local-edits-v1").put("server_id", workspace()).put("operations", result).toString(2);
    }

    /** 以用户看见的冲突版本作为基础；后续编辑纳入同一次重提，不被清空。 */
    public void resolve(String uuid, JSONObject choices, boolean saveCopy) throws Exception {
        final Exception[] failure = {null};
        db.runInTransaction(() -> {
            try {
                List<SyncDatabase.Operation> ops = db.data().operations(workspace(), uuid);
                if (ops.isEmpty()) return;
                JSONObject result = new JSONObject(ops.get(0).error);
                JSONObject current = result.optJSONObject("current");
                JSONObject local = view(workspace(), uuid);
                if ((current == null || current.optBoolean("deleted")) && !saveCopy) throw new JSONException("电脑上已删除，请选择放弃或另存为新日程");
                JSONObject target = saveCopy ? TaskModel.editable(local) : new JSONObject();
                if (!saveCopy) {
                    LinkedHashSet<String> touched = new LinkedHashSet<>();
                    for (SyncDatabase.Operation op : ops) {
                        Iterator<String> fields = new JSONObject(op.changes).keys();
                        while (fields.hasNext()) touched.add(fields.next());
                    }
                    for (String field : touched) target.put(field, choices.optBoolean(field, true) ? local.opt(field) : current.opt(field));
                }
                boolean deletion = "delete".equals(ops.get(ops.size() - 1).kind) && !saveCopy;
                JSONObject check = saveCopy ? TaskModel.defaults(UUID.randomUUID().toString()) : TaskModel.copy(current);
                TaskModel.patch(check, target);
                if (!deletion) TaskModel.validate(check);
                SyncDatabase.Operation replacement = new SyncDatabase.Operation();
                replacement.operationId = UUID.randomUUID().toString(); replacement.workspace = workspace();
                replacement.uuid = saveCopy ? check.getString("uuid") : uuid;
                replacement.kind = saveCopy ? "create" : (deletion ? "delete" : "update");
                replacement.base = TaskModel.editable(saveCopy ? TaskModel.defaults(replacement.uuid) : current).toString();
                replacement.baseVersion = saveCopy ? 0 : current.getLong("version"); replacement.changes = target.toString();
                db.data().clearOperations(workspace(), uuid); db.data().insert(replacement);
            } catch (Exception error) { failure[0] = error; throw new IllegalArgumentException(error); }
        });
        if (failure[0] != null) throw failure[0];
        changed();
    }

    private void putServer(String space, JSONObject task) throws JSONException {
        SyncDatabase.Task old = db.data().task(space, task.getString("uuid"));
        if (old != null && new JSONObject(old.json).optLong("version") > task.optLong("version")) return;
        SyncDatabase.Task row = new SyncDatabase.Task(); row.workspace = space; row.uuid = task.getString("uuid"); row.json = task.toString(); db.data().put(row);
    }
    private void putPage(String space, JSONArray tasks) throws JSONException {
        for (int i = 0; i < tasks.length(); i++) putServer(space, tasks.getJSONObject(i));
    }
    private SyncDatabase.State state(String space) {
        SyncDatabase.State state = db.data().state(space);
        if (state == null) { state = new SyncDatabase.State(); state.workspace = space; }
        return state;
    }
    private static String encode(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private JSONObject call(String path, JSONObject body) throws Exception {
        if (!syncSpace.equals(credentials.server()) || !syncToken.equals(credentials.token()) || !syncBase.equals(credentials.base())) throw new Exception("连接已变化，本次同步已停止；本地编辑仍保留");
        return transport.call(syncBase, path, body, syncToken);
    }
    private String query(JSONObject info) throws Exception { return "?server_id=" + encode(info.getString("server_id")) + "&epoch=" + encode(info.getString("epoch")); }

    void pull(JSONObject info, boolean bootstrap) throws Exception {
        String space = workspace();
        SyncDatabase.State state = state(space);
        String root = bootstrap ? "/api/v1/sync/bootstrap" : "/api/v1/sync/changes";
        JSONArray staged = new JSONArray();
        String key = ""; long upper = -1, cursor = state.cursor;
        for (int pages = 0; pages < 10000; pages++) {
            String path = root + query(info) + "&limit=200" + (upper >= 0 ? "&upper=" + upper : "")
                + (bootstrap ? "&key=" + encode(key) : "&after=" + cursor);
            JSONObject page = call(path, null);
            if (!page.optBoolean("ok") || !info.getString("epoch").equals(page.optString("epoch")) || !space.equals(page.optString("server_id"))) throw new Exception("电脑已重启，下一次同步将重新校准");
            upper = page.getLong("upper");
            JSONArray items = page.getJSONArray("items");
            if (bootstrap) {
                for (int i = 0; i < items.length(); i++) staged.put(items.get(i));
                key = page.getString("key");
            } else {
                final long next = page.getLong("cursor");
                db.runInTransaction(() -> {
                    try { putPage(space, items); state.cursor = next; db.data().put(state); }
                    catch (JSONException error) { throw new IllegalArgumentException(error); }
                });
                cursor = next;
            }
            if (!page.getBoolean("has_more")) {
                if (bootstrap) {
                    final long next = upper;
                    db.runInTransaction(() -> {
                        try { db.data().clearTasks(space); putPage(space, staged); state.cursor = next; state.epoch = info.getString("epoch"); db.data().put(state); }
                        catch (JSONException error) { throw new IllegalArgumentException(error); }
                    });
                }
                changed(); return;
            }
        }
        throw new Exception("同步分页过多，请检查电脑数据");
    }

    /** 返回 false 表示已有同步任务、网络故障或仍有可重试的操作。 */
    public boolean sync() {
        if (!SYNC_LOCK.tryLock()) return false;
        String space = workspace();
        try {
            if (space.isEmpty() || credentials.token().isEmpty()) return true;
            syncBase = credentials.base(); syncToken = credentials.token(); syncSpace = space;
            JSONObject info = call("/api/v1/info", null);
            if (info.optInt("protocol") != 1) throw new Exception("电脑版本不支持离线同步，请升级电脑端");
            if (!space.equals(info.optString("server_id"))) throw new Exception("域名指向另一台电脑，已停止同步；请核对连接");
            pull(info, !state(space).epoch.equals(info.getString("epoch")));
            Set<String> blocked = new HashSet<>();
            int processed = 0;
            for (SyncDatabase.Operation op : db.data().operations(space)) {
                if (blocked.contains(op.uuid)) continue;
                if (op.status.equals("conflict") || op.status.equals("error")) { blocked.add(op.uuid); continue; }
                if (++processed > 100) break;
                if (op.frozen.isEmpty()) {
                    SyncDatabase.Task current = db.data().task(space, op.uuid);
                    long version = current == null ? op.baseVersion : new JSONObject(current.json).optLong("version", op.baseVersion);
                    JSONObject request = new JSONObject().put("server_id", space).put("epoch", info.getString("epoch"))
                        .put("operation_id", op.operationId).put("uuid", op.uuid).put("type", op.kind)
                        .put("base_version", Math.max(op.baseVersion, version)).put("base", new JSONObject(op.base)).put("changes", new JSONObject(op.changes));
                    op.frozen = request.toString(); op.status = "sending"; db.data().update(op);
                }
                JSONObject result;
                try { result = call("/api/v1/mutations", new JSONObject(op.frozen)); }
                catch (SyncTransport.Failure error) {
                    if (error.status != 400) throw error;
                    JSONObject issue = new JSONObject().put("code", "validation").put("error", error.getMessage());
                    SyncDatabase.Task current = db.data().task(space, op.uuid);
                    if (current != null) issue.put("current", new JSONObject(current.json));
                    op.status = "error"; op.error = issue.toString(); db.data().update(op); blocked.add(op.uuid); continue;
                }
                if (result.optBoolean("ok")) {
                    db.runInTransaction(() -> {
                        try { putServer(space, result.getJSONObject("task")); db.data().delete(op); }
                        catch (JSONException error) { throw new IllegalArgumentException(error); }
                    });
                } else if ("reset_required".equals(result.optString("code"))) {
                    // 服务端明确拒绝本次请求，原操作没有提交；生成新的请求 ID 后再校准。
                    op.frozen = ""; op.status = "pending"; op.operationId = UUID.randomUUID().toString(); db.data().update(op);
                    throw new Exception("电脑同步会话已变化，正在等待重新校准");
                } else {
                    op.status = "conflict"; op.error = result.toString();
                    db.runInTransaction(() -> {
                        try { JSONObject current = result.optJSONObject("current"); if (current != null) putServer(space, current); db.data().update(op); }
                        catch (JSONException error) { throw new IllegalArgumentException(error); }
                    });
                    blocked.add(op.uuid);
                }
                changed();
            }
            pull(info, false);
            SyncDatabase.State state = state(space); state.lastSuccess = System.currentTimeMillis(); state.error = ""; db.data().put(state);
            for (SyncDatabase.Operation op : db.data().operations(space)) if (!blocked.contains(op.uuid)) return false;
            return true;
        } catch (Exception error) {
            SyncDatabase.State state = state(space); state.error = error.getMessage() == null ? "同步未完成，请重试" : error.getMessage(); db.data().put(state);
            if (error instanceof SyncTransport.Failure && ((SyncTransport.Failure)error).status == 401) credentials.unpair();
            return false;
        } finally { SYNC_LOCK.unlock(); changed(); }
    }
}
