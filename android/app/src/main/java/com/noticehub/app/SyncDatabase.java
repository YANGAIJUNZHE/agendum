package com.noticehub.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.room.*;
import java.util.List;

@Database(entities = {SyncDatabase.Task.class, SyncDatabase.Operation.class, SyncDatabase.State.class}, version = 1, exportSchema = true)
public abstract class SyncDatabase extends RoomDatabase {
    private static volatile SyncDatabase instance;
    public static SyncDatabase get(Context context) {
        if (instance == null) synchronized (SyncDatabase.class) {
            if (instance == null) instance = Room.databaseBuilder(context.getApplicationContext(), SyncDatabase.class, "schedule-sync.db").build();
        }
        return instance;
    }
    public abstract Data data();

    @Entity(tableName = "tasks", primaryKeys = {"workspace", "uuid"})
    public static class Task {
        @NonNull public String workspace = "";
        @NonNull public String uuid = "";
        @NonNull public String json = "{}";
    }

    @Entity(tableName = "operations", indices = {@Index(value = {"operationId"}, unique = true), @Index(value = {"workspace", "uuid", "id"})})
    public static class Operation {
        @PrimaryKey(autoGenerate = true) public long id;
        @NonNull public String operationId = "";
        @NonNull public String workspace = "";
        @NonNull public String uuid = "";
        @NonNull public String kind = "update";
        @NonNull public String base = "{}";
        @NonNull public String changes = "{}";
        public long baseVersion;
        @NonNull public String frozen = "";
        @NonNull public String status = "pending";
        @NonNull public String error = "";
    }

    @Entity(tableName = "states")
    public static class State {
        @PrimaryKey @NonNull public String workspace = "";
        @NonNull public String epoch = "";
        public long cursor;
        public long lastSuccess;
        @NonNull public String error = "";
    }

    @Dao public interface Data {
        @Query("SELECT * FROM tasks WHERE workspace=:workspace ORDER BY uuid") List<Task> tasks(String workspace);
        @Query("SELECT * FROM tasks WHERE workspace=:workspace AND uuid=:uuid") Task task(String workspace, String uuid);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void put(Task task);
        @Query("DELETE FROM tasks WHERE workspace=:workspace") void clearTasks(String workspace);
        @Query("SELECT * FROM operations WHERE workspace=:workspace ORDER BY id") List<Operation> operations(String workspace);
        @Query("SELECT * FROM operations WHERE workspace=:workspace AND uuid=:uuid ORDER BY id") List<Operation> operations(String workspace, String uuid);
        @Insert long insert(Operation operation);
        @Update void update(Operation operation);
        @Delete void delete(Operation operation);
        @Query("DELETE FROM operations WHERE workspace=:workspace AND uuid=:uuid") void clearOperations(String workspace, String uuid);
        @Query("DELETE FROM operations WHERE workspace=:workspace") void discard(String workspace);
        @Query("SELECT * FROM states WHERE workspace=:workspace") State state(String workspace);
        @Insert(onConflict = OnConflictStrategy.REPLACE) void put(State state);
    }
}
