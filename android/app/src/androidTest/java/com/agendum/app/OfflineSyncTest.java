package com.agendum.app;

import android.content.Context;
import androidx.room.Room;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import org.json.*;
import java.io.IOException;
import java.util.*;
import static org.junit.Assert.*;

/** 真正运行 Room、Keystore、队列和 Repository，仅 HTTP 对端为可控模型。 */
@RunWith(AndroidJUnit4.class)
public class OfflineSyncTest {
    Context context;
    SyncDatabase db;
    Credentials credentials;
    FakeServer server;
    ScheduleRepository repository;
    final String space = "test-workspace";

    @Before public void before() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase("offline-tests.db");
        db = Room.databaseBuilder(context, SyncDatabase.class, "offline-tests.db").allowMainThreadQueries().build();
        credentials = new Credentials(context); credentials.save("https://test.invalid", space, "test-token");
        server = new FakeServer(); repository = new ScheduleRepository(db, credentials, server);
    }
    @After public void after() { ScheduleRepository.listen(null); db.close(); context.deleteDatabase("offline-tests.db"); credentials.unpair(); }
    private String create(String text) throws Exception {
        String id = UUID.randomUUID().toString(); repository.edit(id, "create", new JSONObject().put("summary", text)); return id;
    }
    @Test public void offlineEditsSurviveDatabaseCloseAndSyncInOrder() throws Exception {
        server.offline = true;
        String id = create("离线新建"); repository.edit(id, "update", new JSONObject().put("notes", "离线备注"));
        assertFalse(repository.sync());
        db.close(); db = Room.databaseBuilder(context, SyncDatabase.class, "offline-tests.db").allowMainThreadQueries().build();
        repository = new ScheduleRepository(db, credentials, server);
        assertEquals("离线备注", repository.view(space, id).getString("notes"));
        assertEquals(2, repository.pendingCount()); server.offline = false;
        assertTrue(repository.sync()); assertEquals(0, repository.pendingCount());
        assertEquals("离线备注", server.tasks.get(id).getString("notes")); assertEquals(2, server.accepted);
    }
    @Test public void lostResponseRetriesFrozenRequestAndKeepsLaterEdit() throws Exception {
        String id = create("创建一次");
        server.dropResponse = true;
        server.duringSend = () -> { try { repository.edit(id, "update", new JSONObject().put("notes", "发送途中又编辑")); } catch(Exception e) { throw new RuntimeException(e); } };
        assertFalse(repository.sync());
        String frozen = db.data().operations(space).get(0).frozen;
        assertEquals("发送途中又编辑", repository.view(space, id).getString("notes"));
        assertTrue(repository.sync());
        assertEquals(frozen, server.requests.get(0)); assertEquals(frozen, server.requests.get(1));
        assertEquals(2, server.accepted); assertEquals(1, server.tasks.size());
        assertEquals("发送途中又编辑", server.tasks.get(id).getString("notes"));
    }
    @Test public void allOfflineManagementSurvivesReopeningDatabase() throws Exception {
        server.offline=true;
        String id=create("离线管理");
        repository.edit(id,"update",new JSONObject().put("pinned",true).put("urgent_override",true)
            .put("deadline",new JSONObject().put("kind","date").put("value","2026-11-06"))
            .put("snooze_until",new JSONObject().put("kind","datetime").put("value","2026-11-05T09:00:00+08:00")));
        repository.edit(id,"update",new JSONObject().put("status","done"));
        assertFalse(repository.view(space,id).getBoolean("pinned"));
        assertEquals("none",repository.view(space,id).getJSONObject("snooze_until").getString("kind"));
        repository.edit(id,"update",new JSONObject().put("status","open").put("notes","完成后离线恢复"));
        String removed=create("离线删除"); repository.edit(removed,"delete",new JSONObject());
        db.close(); db=Room.databaseBuilder(context,SyncDatabase.class,"offline-tests.db").allowMainThreadQueries().build();
        repository=new ScheduleRepository(db,credentials,server);
        JSONObject saved=repository.view(space,id);
        assertEquals("open",saved.getString("status")); assertEquals("完成后离线恢复",saved.getString("notes"));
        assertEquals("2026-11-06",saved.getJSONObject("deadline").getString("value")); assertTrue(saved.getBoolean("urgent_override"));
        assertTrue(repository.view(space,removed).getBoolean("deleted"));
        assertEquals(1,repository.snapshot().getJSONArray("tasks").length()); assertEquals(6,repository.pendingCount());
        assertFalse(repository.sync()); assertEquals(0,server.accepted);
    }
    @Test public void nonoverlappingMergeAndConflictResolutionKeepFollowingEdits() throws Exception {
        String id = create("原始标题"); assertTrue(repository.sync());
        repository.edit(id, "update", new JSONObject().put("summary", "手机标题"));
        server.tasks.get(id).put("notes", "电脑备注").put("version", 2);
        assertTrue(repository.sync()); assertEquals("电脑备注", repository.view(space, id).getString("notes"));
        repository.edit(id, "update", new JSONObject().put("summary", "手机新标题"));
        server.tasks.get(id).put("summary", "电脑新标题").put("version", 4);
        assertTrue(repository.sync()); assertEquals("conflict", db.data().operations(space).get(0).status);
        repository.edit(id, "update", new JSONObject().put("notes", "冲突后的备注"));
        repository.resolve(id, new JSONObject().put("summary", true), false);
        assertTrue(repository.sync()); assertEquals(0, repository.pendingCount());
        assertEquals("手机新标题", server.tasks.get(id).getString("summary")); assertEquals("冲突后的备注", server.tasks.get(id).getString("notes"));
    }
    @Test public void oneConflictDoesNotBlockOtherTask() throws Exception {
        String a=create("A"), b=create("B"); repository.sync();
        repository.edit(a,"update",new JSONObject().put("summary","手机A"));
        repository.edit(b,"update",new JSONObject().put("notes","手机B"));
        server.tasks.get(a).put("summary","电脑A").put("version",2);
        assertTrue(repository.sync()); assertEquals(1, repository.pendingCount()); assertEquals("手机B",server.tasks.get(b).getString("notes"));
    }
    @Test public void oldActivityCannotDetachCurrentLocalObserver() throws Exception {
        final int[] counts={0,0};
        Runnable old=()->counts[0]++, current=()->counts[1]++;
        ScheduleRepository.listen(old); ScheduleRepository.listen(current); ScheduleRepository.stopListening(old);
        create("新界面应立即收到本地保存通知");
        assertEquals(0,counts[0]); assertEquals(1,counts[1]);
    }
    @Test public void openEditorKeepsObservedBaseWhenPullArrivesBeforeSave() throws Exception {
        String id=create("编辑前的标题"); assertTrue(repository.sync());
        JSONObject observed=repository.view(space,id);
        server.tasks.get(id).put("summary","电脑在编辑期间修改").put("version",2);
        assertTrue(repository.sync());
        repository.edit(id,"update",new JSONObject().put("summary","手机输入的新标题"),observed);
        assertTrue(repository.sync());
        assertEquals("conflict",db.data().operations(space,id).get(0).status);
        assertEquals("电脑在编辑期间修改",server.tasks.get(id).getString("summary"));
        assertEquals("手机输入的新标题",repository.view(space,id).getString("summary"));
    }
    @Test public void wrongBackendAndRevocationKeepPendingEdits() throws Exception {
        create("保留编辑"); server.wrongIdentity=true;
        assertFalse(repository.sync()); assertEquals(1, repository.pendingCount()); assertEquals(0,server.accepted);
        server.wrongIdentity=false; server.unauthorized=true;
        assertFalse(repository.sync()); assertEquals(1,repository.pendingCount()); assertEquals("",credentials.token());
    }
    @Test public void workspaceIsolationAndExportContainNoToken() throws Exception {
        String id=create("旧电脑的编辑");
        String export=repository.exportPending(); assertFalse(export.contains("test-token")); assertTrue(export.contains(id));
        credentials.save("https://other.invalid","other-workspace","other-token");
        assertEquals(0,repository.pendingCount()); assertEquals(0,repository.snapshot().getJSONArray("tasks").length());
        credentials.save("https://test.invalid",space,"new-token"); assertEquals(1,repository.pendingCount());
    }
    @Test public void remoteDeletionRetainsOfflineTitleAndCanSaveCopy() throws Exception {
        String id=create("需要保留的原始标题"); repository.sync();
        repository.edit(id,"update",new JSONObject().put("notes","还没同步的备注"));
        server.tasks.put(id,new JSONObject().put("uuid",id).put("version",2).put("deleted",true));
        assertTrue(repository.sync());
        JSONObject local=repository.view(space,id);
        assertEquals("需要保留的原始标题",local.getString("summary")); assertEquals("还没同步的备注",local.getString("notes"));
        repository.resolve(id,new JSONObject(),true); assertTrue(repository.sync());
        assertEquals(0,repository.pendingCount());
        assertEquals(2,server.tasks.size());
    }

    final class FakeServer extends SyncTransport {
        final Map<String,JSONObject> tasks = new LinkedHashMap<>(), receipts = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        boolean offline,dropResponse,wrongIdentity,unauthorized;
        Runnable duringSend;
        int accepted;
        @Override public JSONObject call(String base,String path,JSONObject request,String token) throws Exception {
            if(offline)throw new IOException("离线");
            if(unauthorized)throw new SyncTransport.Failure(401,"配对已撤销");
            String sid=wrongIdentity?"another":space;
            if(path.equals("/api/v1/info"))return new JSONObject().put("protocol",1).put("server_id",sid).put("epoch","epoch1");
            if(path.startsWith("/api/v1/sync/")){
                JSONArray items=new JSONArray(); for(JSONObject task:tasks.values())items.put(TaskModel.copy(task));
                return new JSONObject().put("ok",true).put("server_id",sid).put("epoch","epoch1").put("items",items).put("cursor",accepted).put("upper",accepted).put("key","").put("has_more",false);
            }
            requests.add(request.toString());
            String op=request.getString("operation_id"),id=request.getString("uuid");
            if(receipts.containsKey(op))return TaskModel.copy(receipts.get(op));
            JSONObject task=tasks.get(id),changes=request.getJSONObject("changes");
            if(task!=null&&task.optBoolean("deleted")&&!request.getString("type").equals("create"))return new JSONObject().put("ok",false).put("code","deleted").put("current",TaskModel.copy(task));
            if(task==null)task=TaskModel.defaults(id);
            JSONArray conflicts=new JSONArray(); JSONObject previous=request.getJSONObject("base");
            Iterator<String> keys=changes.keys();while(keys.hasNext()){
                String key=keys.next();if(!request.getString("type").equals("create")&&!String.valueOf(task.opt(key)).equals(String.valueOf(previous.opt(key)))&&!String.valueOf(task.opt(key)).equals(String.valueOf(changes.opt(key))))conflicts.put(key);
            }
            if(conflicts.length()>0)return new JSONObject().put("ok",false).put("code","conflict").put("fields",conflicts).put("current",TaskModel.copy(task));
            TaskModel.patch(task,changes);task.put("version",task.optLong("version")+1);tasks.put(id,task);accepted++;
            JSONObject result=new JSONObject().put("ok",true).put("task",TaskModel.copy(task));receipts.put(op,TaskModel.copy(result));
            if(duringSend!=null){Runnable callback=duringSend;duringSend=null;callback.run();}
            if(dropResponse){dropResponse=false;throw new IOException("服务端已提交，响应丢失");}
            return result;
        }
    }
}
