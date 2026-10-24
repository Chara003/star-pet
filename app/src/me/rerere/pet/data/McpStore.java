package me.rerere.pet.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MCP 服务器配置的持久化。 */
public final class McpStore {

    private static final String PREFS = "pet_mcp";
    private static final String KEY_SERVERS = "servers";

    /** 传输类型。 */
    public static final String TRANSPORT_HTTP = "streamable_http";
    public static final String TRANSPORT_SSE = "sse";

    /** 一个 MCP 服务器的配置。 */
    public static final class Server {
        public String id;
        public String name;
        public boolean enabled = true;
        public String transport = TRANSPORT_HTTP;
        public String url = "";
        /** 自定义请求头，key -> value。 */
        public final Map<String, String> headers = new LinkedHashMap<>();
        /** 是否把该服务器的工具列表发给模型。 */
        public boolean shareTools = true;

        public Server() {}

        public Server(String id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("enabled", enabled);
            o.put("transport", transport);
            o.put("url", url);
            o.put("shareTools", shareTools);
            JSONObject h = new JSONObject();
            for (Map.Entry<String, String> e : headers.entrySet()) h.put(e.getKey(), e.getValue());
            o.put("headers", h);
            return o;
        }

        static Server fromJson(JSONObject o) {
            Server s = new Server();
            s.id = o.optString("id", "");
            s.name = o.optString("name", "");
            s.enabled = o.optBoolean("enabled", true);
            s.transport = o.optString("transport", TRANSPORT_HTTP);
            s.url = o.optString("url", "");
            s.shareTools = o.optBoolean("shareTools", true);
            JSONObject h = o.optJSONObject("headers");
            if (h != null) {
                java.util.Iterator<String> it = h.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    s.headers.put(k, h.optString(k, ""));
                }
            }
            return s;
        }

        public boolean isValid() {
            return url != null && !url.trim().isEmpty()
                    && name != null && !name.trim().isEmpty();
        }
    }

    private final SharedPreferences prefs;

    public McpStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Server> servers() {
        List<Server> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_SERVERS, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(Server.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
        return out;
    }

    public void saveServers(List<Server> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Server s : list) arr.put(s.toJson());
            prefs.edit().putString(KEY_SERVERS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public void upsert(Server server) {
        List<Server> all = servers();
        boolean replaced = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(server.id)) {
                all.set(i, server);
                replaced = true;
                break;
            }
        }
        if (!replaced) all.add(server);
        saveServers(all);
    }

    public void remove(String id) {
        List<Server> all = servers();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) { all.remove(i); break; }
        }
        saveServers(all);
    }
}
