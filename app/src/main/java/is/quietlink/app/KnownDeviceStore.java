package is.quietlink.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class KnownDeviceStore {
    private static final String PREF = "quietlink_known_devices";
    private static final String KEY = "devices";
    private final SharedPreferences prefs;

    public KnownDeviceStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public synchronized List<KnownDevice> list() {
        List<KnownDevice> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                KnownDevice d = KnownDevice.from(o);
                if (d != null) out.add(d);
            }
        } catch (Exception ignored) {}
        Collections.sort(out, Comparator.comparing(a -> a.name.toLowerCase()));
        return out;
    }

    public synchronized KnownDevice get(String fingerprint) {
        if (fingerprint == null) return null;
        for (KnownDevice d : list()) if (fingerprint.equals(d.fingerprint)) return d;
        return null;
    }

    public synchronized void remember(String fingerprint, String name, byte[] publicKey) {
        if (fingerprint == null || publicKey == null) return;
        List<KnownDevice> all = list();
        KnownDevice found = null;
        for (KnownDevice d : all) if (fingerprint.equals(d.fingerprint)) { found = d; break; }
        if (found == null) {
            found = new KnownDevice();
            found.fingerprint = fingerprint;
            found.trusted = false;
            found.autoConnect = false;
            all.add(found);
        }
        if (name != null && !name.trim().isEmpty()) found.name = name.trim();
        if (found.name == null || found.name.isEmpty()) found.name = "QuietLink " + DeviceIdentity.shortId(fingerprint);
        found.publicKeyBase64 = Base64.encodeToString(publicKey, Base64.NO_WRAP);
        found.lastSeen = System.currentTimeMillis();
        save(all);
    }

    public synchronized void setTrusted(String fingerprint, boolean trusted) {
        List<KnownDevice> all = list();
        for (KnownDevice d : all) {
            if (fingerprint.equals(d.fingerprint)) {
                d.trusted = trusted;
                if (!trusted) d.autoConnect = false;
            }
        }
        save(all);
    }

    public synchronized void setAutoConnect(String fingerprint, boolean auto) {
        List<KnownDevice> all = list();
        for (KnownDevice d : all) {
            if (fingerprint.equals(d.fingerprint)) {
                d.trusted = auto || d.trusted;
                d.autoConnect = auto;
            }
        }
        save(all);
    }

    public synchronized void rename(String fingerprint, String name) {
        if (name == null || name.trim().isEmpty()) return;
        List<KnownDevice> all = list();
        for (KnownDevice d : all) if (fingerprint.equals(d.fingerprint)) d.name = name.trim();
        save(all);
    }

    public synchronized void forget(String fingerprint) {
        List<KnownDevice> all = list();
        all.removeIf(d -> fingerprint.equals(d.fingerprint));
        save(all);
    }

    private void save(List<KnownDevice> all) {
        JSONArray arr = new JSONArray();
        try {
            for (KnownDevice d : all) arr.put(d.toJson());
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static final class KnownDevice {
        public String fingerprint;
        public String name;
        public String publicKeyBase64;
        public boolean trusted;
        public boolean autoConnect;
        public long lastSeen;

        public byte[] publicKey() {
            try { return Base64.decode(publicKeyBase64, Base64.DEFAULT); }
            catch (Exception e) { return new byte[0]; }
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("fingerprint", fingerprint);
            o.put("name", name);
            o.put("publicKey", publicKeyBase64);
            o.put("trusted", trusted);
            o.put("auto", autoConnect);
            o.put("lastSeen", lastSeen);
            return o;
        }

        static KnownDevice from(JSONObject o) {
            String fp = o.optString("fingerprint", "");
            String pub = o.optString("publicKey", "");
            if (fp.isEmpty() || pub.isEmpty()) return null;
            KnownDevice d = new KnownDevice();
            d.fingerprint = fp;
            d.name = o.optString("name", "QuietLink " + DeviceIdentity.shortId(fp));
            d.publicKeyBase64 = pub;
            d.trusted = o.optBoolean("trusted", false);
            d.autoConnect = o.optBoolean("auto", false);
            d.lastSeen = o.optLong("lastSeen", 0);
            return d;
        }
    }
}
