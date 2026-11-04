package com.noticehub.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import androidx.core.view.*;
import com.google.zxing.integration.android.*;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activity 负责页面、配对和原生桥；数据库与同步由 Repository 持久化。 */
public final class MainActivity extends Activity {
    private final ExecutorService dataWorker = Executors.newSingleThreadExecutor(), networkWorker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean syncBusy = new AtomicBoolean(false);
    private Credentials credentials;
    private ScheduleRepository repository;
    private final Runnable dataChanged = this::render;
    private WebView web;
    private boolean showingClient, loaded, resumed, scanInProgress;
    private EditText address;
    private TextView status, code;
    private String pairingBase, pairId, pairSecret, pendingExport, baseAfterExport;
    private JSONObject pairAfterExport;
    private final Runnable foreground = new Runnable() {
        @Override public void run() { if (resumed && showingClient) { sync(); handler.postDelayed(this, 10000); } }
    };
    private final Runnable pairPoll = () -> exchange(pairingBase, pairId, pairSecret);
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        credentials = new Credentials(this); repository = new ScheduleRepository(this);
        ScheduleRepository.listen(dataChanged);
        if (handleLink(getIntent() == null ? null : getIntent().getData())) return;
        if (!credentials.server().isEmpty()) showClient(); else showPairing();
        ScheduleSyncWorker.schedule(this, false);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleLink(intent == null ? null : intent.getData()); }
    private boolean handleLink(Uri uri) {
        if (uri == null || !"noticehub".equalsIgnoreCase(uri.getScheme()) || !"connect".equalsIgnoreCase(uri.getHost())) return false;
        if (!credentials.token().isEmpty()) { if (!showingClient) showClient(); toast("已连接，如需换电脑请先在连接页解绑"); return true; }
        String base = normalize(uri.getQueryParameter("base")), ident = uri.getQueryParameter("pair_id"), secret = uri.getQueryParameter("pair_secret");
        showPairing();
        if (base.isEmpty() || ident == null || secret == null) { setStatus("这是旧版或无效二维码，请在电脑「手机日程同步」中重新生成"); return true; }
        setStatus("正在交换一次性配对凭据…"); exchange(base, ident, secret); return true;
    }
    private static String normalize(String value) {
        return NetworkEndpoint.normalize(value);
    }
    private void insets(View view) {
        int l = view.getPaddingLeft(), t = view.getPaddingTop(), r = view.getPaddingRight(), b = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, w) -> {
            androidx.core.graphics.Insets bars = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom); return w;
        }); ViewCompat.requestApplyInsets(view);
    }
    private void showPairing() {
        pairId = null; pairSecret = null;
        showingClient = false; loaded = false; handler.removeCallbacks(foreground); handler.removeCallbacks(pairPoll);
        setContentView(R.layout.activity_main); insets(findViewById(R.id.root));
        address = findViewById(R.id.address); status = findViewById(R.id.status); code = findViewById(R.id.pair_code);
        String old = getSharedPreferences("notice_hub", MODE_PRIVATE).getString("base", BuildConfig.PAIR_SERVER);
        address.setText(credentials.base().isEmpty() ? old : credentials.base());
        View form = findViewById(R.id.manual_form);
        findViewById(R.id.manual_toggle).setOnClickListener(v -> form.setVisibility(form.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        findViewById(R.id.open).setOnClickListener(v -> startPairing());
        address.setOnEditorActionListener((v, action, event) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_GO && action != android.view.inputmethod.EditorInfo.IME_ACTION_DONE) return false;
            startPairing(); return true;
        });
        findViewById(R.id.scan).setOnClickListener(v -> scan());
        if (!credentials.server().isEmpty()) {
            Button offline = new Button(this); offline.setText("返回离线日程"); offline.setOnClickListener(v -> showClient());
            ((LinearLayout)((ScrollView)findViewById(R.id.root)).getChildAt(0)).addView(offline);
        } else if (!getSharedPreferences("notice_hub", MODE_PRIVATE).getString("token", "").isEmpty()) {
            setStatus("本版需要重新配对。旧快照仍保留；请在电脑设置中打开「手机日程同步」");
        }
        if (credentials.server().isEmpty() && new File(getFilesDir(), "snapshot.json").isFile()) {
            Button legacy = new Button(this); legacy.setText("查看升级前的日程（只读）");
            legacy.setOnClickListener(v -> showLegacySnapshot());
            ((LinearLayout)((ScrollView)findViewById(R.id.root)).getChildAt(0)).addView(legacy);
        }
    }
    private void showLegacySnapshot() {
        dataWorker.execute(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(getFilesDir(), "snapshot.json")), "UTF-8"))) {
                StringBuilder json = new StringBuilder(); String line;
                while ((line = reader.readLine()) != null) json.append(line);
                JSONObject snapshot = new JSONObject(json.toString()), board = snapshot.getJSONObject("tasks");
                StringBuilder text = new StringBuilder("这是升级前保存的快照。重新配对并同步后，才能编辑日程。\n");
                long saved = snapshot.optLong("savedAt");
                if (saved > 0) text.append("\n保存于 ").append(java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(saved))).append('\n');
                java.util.HashSet<String> seen = new java.util.HashSet<>();
                java.util.Iterator<String> groups = board.keys();
                while (groups.hasNext()) {
                    JSONArray tasks = board.optJSONArray(groups.next()); if (tasks == null) continue;
                    for (int i = 0; i < tasks.length(); i++) {
                        JSONObject task = tasks.optJSONObject(i); if (task == null) continue;
                        String id = task.optString("id", task.optString("summary")); if (!seen.add(id)) continue;
                        text.append("\n• ").append(task.optString("summary", "未命名日程"));
                        if (!task.optString("deadline").isEmpty()) text.append("\n截止：").append(task.optString("deadline"));
                        text.append('\n');
                    }
                }
                if (seen.isEmpty()) text.append("\n旧快照中没有可显示的日程。");
                runOnUiThread(() -> {
                    TextView body = new TextView(this); body.setPadding(32, 16, 32, 24); body.setText(text); body.setTextIsSelectable(true);
                    ScrollView scroll = new ScrollView(this); scroll.addView(body);
                    new AlertDialog.Builder(this).setTitle("升级前的日程").setView(scroll).setPositiveButton("关闭", null).show();
                });
            } catch (Exception error) { toast("无法读取旧快照；原文件仍保留，请先重新配对"); }
        });
    }
    private void setStatus(String text) { runOnUiThread(() -> { if (status != null && !showingClient) { findViewById(R.id.manual_form).setVisibility(View.VISIBLE); status.setText(text); } }); }
    private void startPairing() {
        String base = normalize(address.getText().toString());
        if (base.isEmpty()) { setStatus("请填写 HTTPS 地址，例如 https://sync.example.com"); return; }
        handler.removeCallbacks(pairPoll); setStatus("正在连接电脑…");
        networkWorker.execute(() -> {
            try {
                SyncTransport transport = new SyncTransport();
                JSONObject info = transport.call(base, "/api/v1/info", null, null);
                if (info.optInt("protocol") != 1) throw new Exception("电脑版本不支持离线同步，请升级电脑端");
                JSONObject ticket = transport.call(base, "/api/v1/pair/start", new JSONObject().put("name", Build.MANUFACTURER + " " + Build.MODEL), null);
                pairingBase = base; pairId = ticket.getString("id"); pairSecret = ticket.getString("secret");
                runOnUiThread(() -> { code.setText(ticket.optString("code")); setStatus("请在电脑「手机日程同步」中核对数字并允许连接"); handler.postDelayed(pairPoll, 2000); });
            } catch (Exception error) { setStatus(message(error)); }
        });
    }
    private void exchange(String base, String ident, String secret) {
        networkWorker.execute(() -> {
            try {
                JSONObject result = new SyncTransport().call(base, "/api/v1/pair/exchange", new JSONObject().put("id", ident).put("secret", secret), null);
                if (!"approved".equals(result.optString("status"))) { runOnUiThread(() -> { if (!showingClient && resumed) handler.postDelayed(pairPoll, 2000); }); return; }
                int pending = repository.pendingCount();
                if (!credentials.server().isEmpty() && !credentials.server().equals(result.getString("server_id")) && pending > 0) {
                    runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("切换到另一台电脑？").setMessage("当前还有 " + pending + " 项未同步操作。先导出，或明确丢弃后再切换。")
                        .setPositiveButton("导出后切换", (d, w) -> { pairAfterExport = result; baseAfterExport = base; export(); })
                        .setNeutralButton("丢弃后切换", (d, w) -> dataWorker.execute(() -> { repository.discardAll(); acceptPair(base, result); }))
                        .setNegativeButton("取消", null).show());
                } else acceptPair(base, result);
            } catch (Exception error) { setStatus(message(error)); }
        });
    }
    private void acceptPair(String base, JSONObject result) {
        try {
            credentials.save(base, result.getString("server_id"), result.getString("token"));
            getSharedPreferences("notice_hub", MODE_PRIVATE).edit().remove("token").apply();
            runOnUiThread(() -> { toast("已连接，正在同步日程"); showClient(); ScheduleSyncWorker.schedule(this, true); });
        } catch (Exception error) { setStatus(message(error)); }
    }
    private void scan() {
        if (scanInProgress) return;
        try { scanInProgress = true; new IntentIntegrator(this).setDesiredBarcodeFormats(java.util.Collections.singletonList("QR_CODE"))
            .setPrompt("扫描电脑「手机日程同步」中的二维码").setBeepEnabled(false).setOrientationLocked(false).initiateScan(); }
        catch (RuntimeException error) { scanInProgress = false; setStatus("无法打开扫码器，请允许相机权限或手动填写地址"); }
    }
    private void showClient() {
        handler.removeCallbacks(pairPoll); showingClient = true; loaded = false;
        if (web != null) web.destroy();
        web = new WebView(this);
        web.setBackgroundColor(android.graphics.Color.rgb(244, 245, 239));
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setAllowFileAccessFromFileURLs(false); settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.setWebChromeClient(new WebChromeClient());
        FrameLayout container = new FrameLayout(this); container.addView(web); setContentView(container); insets(container);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) { loaded = true; render(); sync(); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) { return openExternal(request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) { return openExternal(Uri.parse(url)); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if ("file:///android_asset/client.html".equals(request.getUrl().toString())) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) runOnUiThread(() -> { showPairing(); setStatus("手机页面加载失败，请重新打开 App"); });
            }
        });
        web.loadUrl("file:///android_asset/client.html");
        handler.removeCallbacks(foreground); if (resumed) handler.postDelayed(foreground, 10000);
    }
    private boolean openExternal(Uri uri) {
        if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (RuntimeException error) { toast("手机没有可用的浏览器"); }
        } return true;
    }
    private void render() {
        if (isFinishing() || dataWorker.isShutdown()) return;
        dataWorker.execute(() -> {
            try { String json = repository.snapshot().toString(); runOnUiThread(() -> { if (showingClient && loaded && web != null) web.evaluateJavascript("window.NH_render(" + json + ");", null); }); }
            catch (Exception error) { toast(message(error)); }
        });
    }
    private void sync() {
        if (networkWorker.isShutdown() || !syncBusy.compareAndSet(false, true)) return;
        networkWorker.execute(() -> { try { repository.sync(); } finally { syncBusy.set(false); render(); } });
    }
    private void export() {
        dataWorker.execute(() -> {
            try {
                pendingExport = repository.exportPending();
                runOnUiThread(() -> {
                    try { startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE, "群务台-未同步编辑.json"), 701); }
                    catch (RuntimeException error) { toast("无法打开文件选择器，请先保留本地数据"); }
                });
            } catch (Exception error) { toast(message(error)); }
        });
    }
    private void unpair() {
        dataWorker.execute(() -> {
            int pending = repository.pendingCount();
            runOnUiThread(() -> {
                if (pending > 0) new AlertDialog.Builder(this).setTitle("还有 " + pending + " 项未同步操作")
                    .setItems(new String[]{"保留本地编辑，重新配对", "导出未同步编辑", "丢弃编辑并解绑", "取消"}, (d, which) -> {
                        if (which == 0) { credentials.unpair(); showPairing(); }
                        else if (which == 1) export();
                        else if (which == 2) new AlertDialog.Builder(this).setMessage("确定丢弃这台手机的全部未同步编辑？")
                            .setNegativeButton("取消", null).setPositiveButton("丢弃并解绑", (a, b) -> dataWorker.execute(() -> { repository.discardAll(); credentials.unpair(); runOnUiThread(this::showPairing); })).show();
                    }).show();
                else new AlertDialog.Builder(this).setTitle("重新配对？").setMessage("本地日程会保留，之后可以离线查看。")
                    .setNegativeButton("取消", null).setPositiveButton("重新配对", (d, w) -> { credentials.unpair(); showPairing(); }).show();
            });
        });
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 701) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                Uri target = data.getData(); String text = pendingExport;
                dataWorker.execute(() -> {
                    try (OutputStream out = getContentResolver().openOutputStream(target)) {
                        if (out == null) throw new IOException("无法写入文件"); out.write(text.getBytes("UTF-8")); toast("已导出未同步编辑");
                        if (pairAfterExport != null) { acceptPair(baseAfterExport, pairAfterExport); pairAfterExport = null; }
                    } catch (Exception error) { toast(message(error)); }
                });
            } else { pairAfterExport = null; baseAfterExport = null; } return;
        }
        scanInProgress = false;
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null || result.getContents() == null) { setStatus("扫码未完成，可重试或手动填写地址"); return; }
        try { if (!handleLink(Uri.parse(result.getContents()))) setStatus("这不是群务台的配对二维码"); }
        catch (RuntimeException error) { setStatus("二维码无法读取，请重新生成"); }
    }
    private static String message(Exception error) { Throwable cause = error; while (cause.getCause() != null) cause = cause.getCause(); return cause.getMessage() == null ? "操作未完成，请重试" : cause.getMessage(); }
    private void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show()); }
    @Override protected void onResume() { super.onResume(); ScheduleRepository.listen(dataChanged); resumed = true; handler.removeCallbacks(foreground); if (showingClient) { render(); sync(); handler.postDelayed(foreground, 10000); } else if (pairId != null) handler.postDelayed(pairPoll, 2000); }
    @Override protected void onPause() { resumed = false; handler.removeCallbacks(foreground); handler.removeCallbacks(pairPoll); super.onPause(); }
    @Override public void onBackPressed() {
        if (showingClient && web != null) web.evaluateJavascript("window.NH_back && window.NH_back();", result -> { if (!"true".equals(result)) MainActivity.super.onBackPressed(); });
        else super.onBackPressed();
    }
    @Override public boolean onKeyLongPress(int keyCode, KeyEvent event) { if (keyCode == KeyEvent.KEYCODE_BACK) { unpair(); return true; } return super.onKeyLongPress(keyCode, event); }
    @Override protected void onDestroy() { ScheduleRepository.stopListening(dataChanged); handler.removeCallbacksAndMessages(null); dataWorker.shutdown(); networkWorker.shutdown(); if (web != null) web.destroy(); super.onDestroy(); }
    public final class Bridge {
        @JavascriptInterface public String query() { try { return repository.snapshot().toString(); } catch (Exception error) { return "{}"; } }
        @JavascriptInterface public void refresh() { render(); sync(); }
        @JavascriptInterface public void reconnect() { runOnUiThread(() -> { if (credentials.token().isEmpty()) showPairing(); else unpair(); }); }
        @JavascriptInterface public void exportPending() { export(); }
        @JavascriptInterface public void command(String json) {
            if (json == null || json.length() > 65536) return;
            dataWorker.execute(() -> {
                try {
                    JSONObject c = new JSONObject(json); String kind = c.getString("type"), uuid = c.optString("uuid");
                    if (kind.equals("resolve")) repository.resolve(uuid, c.optJSONObject("choices") == null ? new JSONObject() : c.getJSONObject("choices"), c.optBoolean("saveCopy"));
                    else if (kind.equals("discard")) repository.discard(uuid);
                    else repository.edit(uuid, kind, c.optJSONObject("changes") == null ? new JSONObject() : c.getJSONObject("changes"), c.optJSONObject("base"));
                    render();
                    runOnUiThread(() -> { if (web != null && showingClient) web.evaluateJavascript("window.NH_saved();", null); });
                    ScheduleSyncWorker.schedule(MainActivity.this, true); sync();
                } catch (Exception error) { String msg = JSONObject.quote(message(error)); runOnUiThread(() -> { if (web != null && showingClient) web.evaluateJavascript("window.NH_error(" + msg + ");", null); }); }
            });
        }
    }
}
