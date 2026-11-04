package com.noticehub.app;

import org.json.*;
import java.net.*;
import java.io.*;

public class SyncTransport {
    public static final class Failure extends IOException {
        public final int status;
        Failure(int status, String message) { super(message); this.status = status; }
    }
    public JSONObject call(String base, String path, JSONObject body, String token) throws Exception {
        base = NetworkEndpoint.normalize(base);
        if (base.isEmpty()) throw new IOException("手机同步必须使用 HTTPS 地址，请重新配对；本地编辑仍保留");
        HttpURLConnection connection = (HttpURLConnection)new URL(base + path).openConnection();
        connection.setConnectTimeout(12000); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        if (token != null && !token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes("UTF-8")); }
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (input == null) throw new Failure(code, "电脑服务暂未返回结果");
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
                String line; while ((line = reader.readLine()) != null) {
                    text.append(line);
                    if (text.length() > 8 * 1024 * 1024) throw new IOException("同步数据过大，请检查电脑服务");
                }
            }
            JSONObject result;
            try { result = new JSONObject(text.toString()); }
            catch (JSONException invalid) { throw new Failure(code, "入口没有返回日程接口，请检查域名和电脑版本"); }
            if (code != 200) throw new Failure(code, result.optString("error", "连接失败：" + code));
            return result;
        } finally { connection.disconnect(); }
    }
}
