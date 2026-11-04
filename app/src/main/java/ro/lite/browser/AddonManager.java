package ro.lite.browser;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Addon-uri pentru site-uri:
 *  - "js"    = script rulat pe pagina (compatibil cu userscript-urile simple de pe Greasy Fork);
 *  - "css"   = stil adaugat pe pagina (compatibil cu stilurile simple de pe UserStyles);
 *  - "block" = blocarea reclamelor si a trackerelor (cereri blocate inainte sa se descarce).
 */
final class AddonManager {

    static final class Addon {
        String id;
        String name;
        String match = "*";   // domenii separate prin virgula, sau * pentru toate
        String type = "js";   // js | css | block
        String code = "";
        boolean enabled = true;
        boolean builtin = false;
        boolean adGroup = false;   // merge doar cand blocarea reclamelor e pornita
    }

    // Domenii de reclame si trackere des intalnite. Blocate inainte sa se descarce:
    // pagini mai rapide si mai putin RAM folosit.
    private static final String[] AD_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
            "pagead2.googlesyndication.com", "google-analytics.com", "googletagmanager.com",
            "googletagservices.com", "amazon-adsystem.com", "adnxs.com", "taboola.com", "outbrain.com",
            "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com", "openx.net", "casalemedia.com",
            "scorecardresearch.com", "hotjar.com", "moatads.com", "adsrvr.org", "smartadserver.com",
            "quantserve.com", "advertising.com", "yieldmo.com", "media.net", "zedo.com", "popads.net",
            "propellerads.com", "adform.net", "teads.tv", "bidswitch.net", "3lift.com", "sharethrough.com",
            "onesignal.com", "mgid.com", "revcontent.com", "exoclick.com", "adcash.com",
    };

    private final SharedPreferences prefs;
    final List<Addon> list = new ArrayList<>();
    final AtomicInteger blocked = new AtomicInteger();
    volatile boolean adblockOn = true;

    private final String mfAdblockCode;

    AddonManager(SharedPreferences prefs, String mfAdblockCode) {
        this.prefs = prefs;
        this.mfAdblockCode = mfAdblockCode == null ? "" : mfAdblockCode;
        load();
    }

    /** Butonul de pornit/oprit: controleaza blocarea reclamelor si scripturile anti-reclame. */
    void setAdblock(boolean on) {
        for (Addon a : list) if ("b_adblock".equals(a.id)) a.enabled = on;
        save();
    }

    // ------------------------------------------------------------------ addon-uri incluse

    private List<Addon> builtins() {
        List<Addon> b = new ArrayList<>();

        Addon ads = new Addon();
        ads.id = "b_adblock";
        ads.name = "Blocare reclame și trackere";
        ads.type = "block";
        ads.enabled = true;
        ads.builtin = true;
        b.add(ads);

        if (!mfAdblockCode.isEmpty()) {
            Addon mf = new Addon();
            mf.id = "b_mfadblock";
            mf.name = "mf-adblock (curăță reclamele de pe Fandom)";
            mf.type = "js";
            mf.match = "fandom.com";
            mf.enabled = true;
            mf.builtin = true;
            mf.adGroup = true;
            mf.code = mfAdblockCode;
            b.add(mf);
        }

        Addon dark = new Addon();
        dark.id = "b_dark";
        dark.name = "Mod întunecat forțat";
        dark.type = "css";
        dark.enabled = false;
        dark.builtin = true;
        dark.code = "html{filter:invert(1) hue-rotate(180deg)!important;background:#fff!important}"
                + "img,video,picture,canvas,iframe,svg image{filter:invert(1) hue-rotate(180deg)!important}";
        b.add(dark);

        Addon noAuto = new Addon();
        noAuto.id = "b_noautoplay";
        noAuto.name = "Fără pornire automată a clipurilor";
        noAuto.type = "js";
        noAuto.enabled = false;
        noAuto.builtin = true;
        noAuto.code = "var t=0;"
                + "document.addEventListener('touchstart',function(){t=Date.now();},true);"
                + "document.addEventListener('play',function(e){"
                + "if(Date.now()-t>1500&&e.target&&e.target.pause)e.target.pause();},true);";
        b.add(noAuto);

        Addon noSelect = new Addon();
        noSelect.id = "b_allowcopy";
        noSelect.name = "Permite copierea textului";
        noSelect.type = "css";
        noSelect.enabled = false;
        noSelect.builtin = true;
        noSelect.code = "*{-webkit-user-select:text!important;user-select:text!important}";
        b.add(noSelect);

        return b;
    }

    // ------------------------------------------------------------------ salvare

    void load() {
        list.clear();
        Map<String, Addon> saved = new HashMap<>();
        List<Addon> custom = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString("addons", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Addon a = new Addon();
                a.id = o.getString("id");
                a.name = o.optString("name", "Addon");
                a.match = o.optString("match", "*");
                a.type = o.optString("type", "js");
                a.code = o.optString("code", "");
                a.enabled = o.optBoolean("enabled", true);
                a.builtin = o.optBoolean("builtin", false);
                if (a.builtin) saved.put(a.id, a);
                else custom.add(a);
            }
        } catch (Exception ignored) {
        }
        // addon-urile incluse: codul vine mereu din aplicatie, starea pornit/oprit din salvare
        for (Addon b : builtins()) {
            Addon s = saved.get(b.id);
            if (s != null) b.enabled = s.enabled;
            list.add(b);
        }
        list.addAll(custom);
        refreshFlags();
    }

    void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Addon a : list) {
                JSONObject o = new JSONObject();
                o.put("id", a.id);
                o.put("name", a.name);
                o.put("match", a.match);
                o.put("type", a.type);
                o.put("code", a.builtin ? "" : a.code);
                o.put("enabled", a.enabled);
                o.put("builtin", a.builtin);
                arr.put(o);
            }
            prefs.edit().putString("addons", arr.toString()).apply();
        } catch (Exception ignored) {
        }
        refreshFlags();
    }

    private void refreshFlags() {
        boolean on = false;
        for (Addon a : list) if ("block".equals(a.type) && a.enabled) on = true;
        adblockOn = on;
    }

    static String newId() {
        return "u" + System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ aplicare pe pagini

    boolean isAdHost(String host) {
        if (!adblockOn || host == null) return false;
        for (String h : AD_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    static boolean matches(Addon a, String host) {
        if (a.match == null || a.match.trim().isEmpty() || a.match.trim().equals("*")) return true;
        if (host == null) return false;
        for (String part : a.match.split(",")) {
            String p = part.trim().toLowerCase();
            if (p.startsWith("*.")) p = p.substring(2);
            if (p.startsWith("www.")) p = p.substring(4);
            if (p.isEmpty()) continue;
            if (p.equals("*") || host.equals(p) || host.endsWith("." + p)) return true;
        }
        return false;
    }

    /** Codul JavaScript care aplica toate addon-urile pornite pentru site-ul dat (sau null). */
    String injectionFor(String host) {
        StringBuilder sb = new StringBuilder();
        for (Addon a : list) {
            if (!a.enabled || !matches(a, host) || a.code == null || a.code.isEmpty()) continue;
            if (a.adGroup && !adblockOn) continue;
            String guard = "__lb_" + a.id.replaceAll("[^A-Za-z0-9_]", "_");
            if ("css".equals(a.type)) {
                sb.append("(function(){if(document.getElementById('").append(guard).append("'))return;")
                        .append("var s=document.createElement('style');s.id='").append(guard).append("';")
                        .append("s.textContent=").append(JSONObject.quote(a.code)).append(";")
                        .append("(document.head||document.documentElement).appendChild(s);})();\n");
            } else if ("js".equals(a.type)) {
                sb.append("(function(){if(window.").append(guard).append(")return;window.")
                        .append(guard).append("=1;")
                        // functii minime ca sa mearga userscript-urile simple
                        .append("var unsafeWindow=window;")
                        .append("var GM_addStyle=function(c){var s=document.createElement('style');")
                        .append("s.textContent=c;(document.head||document.documentElement).appendChild(s);return s;};")
                        .append("try{\n").append(a.code).append("\n}catch(e){console.log('addon',e);}})();\n");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ------------------------------------------------------------------ import din link

    static String fetch(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 1_000_000) throw new Exception("Fișierul e prea mare");
            }
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Transforma un userscript (.user.js) sau un stil (.css) intr-un addon. */
    static Addon fromText(String text, String url) {
        Addon a = new Addon();
        a.id = newId();
        a.code = text;
        boolean isCss = url.toLowerCase().contains(".css") && !text.contains("==UserScript==");
        a.type = isCss ? "css" : "js";

        String fileName = url.substring(url.lastIndexOf('/') + 1);
        int q = fileName.indexOf('?');
        if (q >= 0) fileName = fileName.substring(0, q);
        a.name = fileName.isEmpty() ? "Addon importat" : fileName;

        List<String> hosts = new ArrayList<>();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (!l.startsWith("//")) {
                if (l.contains("==/UserScript==")) break;
                continue;
            }
            l = l.substring(2).trim();
            if (l.startsWith("@name ")) {
                a.name = l.substring(6).trim();
            } else if (l.startsWith("@match ") || l.startsWith("@include ")) {
                String pat = l.substring(l.indexOf(' ') + 1).trim();
                String h = hostFromPattern(pat);
                if (h != null && !hosts.contains(h)) hosts.add(h);
            }
            if (l.contains("==/UserScript==")) break;
        }
        a.match = hosts.isEmpty() || hosts.contains("*") ? "*" : String.join(", ", hosts);
        return a;
    }

    private static String hostFromPattern(String pat) {
        if (pat.equals("*") || pat.equals("<all_urls>")) return "*";
        int s = pat.indexOf("://");
        String rest = s >= 0 ? pat.substring(s + 3) : pat;
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        if (rest.startsWith("*.")) rest = rest.substring(2);
        if (rest.startsWith("www.")) rest = rest.substring(4);
        if (rest.isEmpty() || rest.equals("*")) return "*";
        if (rest.contains("*")) return null;   // tipare complicate: le ignoram
        return rest.toLowerCase();
    }
}
