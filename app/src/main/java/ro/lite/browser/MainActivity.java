package ro.lite.browser;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lite Browser: browser minimal pe baza WebView-ului din sistem.
 *
 * Cum tine RAM-ul jos:
 *  - doar un numar mic de taburi sunt "active" (au pagina incarcata in memorie);
 *    restul sunt "adormite": se pastreaza doar adresa, iar pagina se reincarca la deschidere;
 *  - tabul vizibil e singurul care ruleaza; cele ascunse sunt puse pe pauza;
 *  - cand iesi din aplicatie sau telefonul are putin RAM, taburile din fundal sunt adormite automat;
 *  - fara biblioteci externe, aplicatia in sine ocupa foarte putin.
 *
 * Login-urile raman salvate: cookie-urile si datele site-urilor sunt pastrate permanent
 * si nu sunt sterse niciodata de aplicatie.
 */
public class MainActivity extends Activity {

    // nume, adresa, culoare, mod desktop
    private static final String[][] SITES = {
            {"Instagram", "https://www.instagram.com/", "#E1306C", "0"},
            {"TikTok", "https://www.tiktok.com/", "#161616", "0"},
            {"Messenger", "https://www.messenger.com/", "#0084FF", "1"},
    };

    private static final String BG = "#121212";
    private static final int FILE_REQ = 1;
    private static final int MIN_FREE_PHONE_MB = 350;

    static class Tab {
        String url;
        String title = "";
        boolean desktop;
        WebView wv;          // null = tab adormit
        long lastUsed;
    }

    private final List<Tab> tabs = new ArrayList<>();
    private Tab current;     // null = pagina de start
    private int maxLive = 3;
    private boolean keepAlive = false;

    private FrameLayout content;
    private View homeView;
    private LinearLayout homeTabsList;
    private EditText urlBar;
    private TextView tabsBtn;
    private TextView ramLabel;
    private ProgressBar progress;

    private String mobileUA;
    private String desktopUA;

    private ValueCallback<Uri[]> fileCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // ------------------------------------------------------------------ ciclul de viata

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("lite", MODE_PRIVATE);
        maxLive = prefs.getInt("maxLive", 3);
        keepAlive = prefs.getBoolean("keepAlive", false);

        getWindow().setStatusBarColor(Color.parseColor(BG));
        getWindow().setNavigationBarColor(Color.parseColor(BG));

        // User-agent fara marcajul "wv", ca site-urile (Facebook, Instagram)
        // sa trateze aplicatia ca pe Chrome si sa permita login-ul.
        String ua = WebSettings.getDefaultUserAgent(this);
        mobileUA = ua.replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        desktopUA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chromeVersion(mobileUA) + " Safari/537.36";

        CookieManager.getInstance().setAcceptCookie(true);

        buildUi();
        restoreTabs();
        showHome();
        if (keepAlive) startKeepAlive();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WebView any = anyLiveWebView();
        if (any != null) any.resumeTimers();
        if (current != null && current.wv != null) current.wv.onResume();
        handler.removeCallbacks(ramTicker);
        handler.post(ramTicker);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ramTicker);
        if (!keepAlive) {
            if (current != null && current.wv != null) current.wv.onPause();
            WebView any = anyLiveWebView();
            if (any != null) any.pauseTimers();
        }
        saveTabs();
        CookieManager.getInstance().flush();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // Include si momentul in care aplicatia iese de pe ecran:
        // pastram activ doar tabul curent.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            sleepBackgroundTabs();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveTabs();
        CookieManager.getInstance().flush();
        for (Tab t : tabs) sleep(t);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (current != null && current.wv != null && current.wv.canGoBack()) {
            current.wv.goBack();
            return;
        }
        if (current != null) {
            showHome();
            return;
        }
        // pe pagina de start: trimite aplicatia in fundal fara sa inchida taburile
        moveTaskToBack(true);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_REQ && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    // ------------------------------------------------------------------ interfata

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable round(String color, int radiusDp, String strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.parseColor(color));
        g.setCornerRadius(dp(radiusDp));
        if (strokeColor != null) g.setStroke(dp(1), Color.parseColor(strokeColor));
        return g;
    }

    private TextView barButton(String text) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(18);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round("#2A2A2A", 12, null));
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(BG));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(6), dp(6), dp(6), dp(4));

        TextView home = barButton("⌂");
        home.setOnClickListener(v -> showHome());

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setHint("Caută sau scrie adresa");
        urlBar.setTextColor(Color.WHITE);
        urlBar.setHintTextColor(Color.GRAY);
        urlBar.setTextSize(14);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setBackground(round("#2A2A2A", 20, null));
        urlBar.setPadding(dp(14), 0, dp(14), 0);
        urlBar.setOnEditorActionListener((v, actionId, ev) -> {
            go(urlBar.getText().toString());
            return true;
        });

        tabsBtn = barButton("0");
        tabsBtn.setTextSize(15);
        tabsBtn.setTypeface(Typeface.DEFAULT_BOLD);
        tabsBtn.setOnClickListener(v -> showTabs());

        TextView menu = barButton("⋮");
        menu.setOnClickListener(this::showMenu);

        bar.addView(home, new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        ulp.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(urlBar, ulp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(40), dp(40));
        tlp.setMargins(0, 0, dp(6), 0);
        bar.addView(tabsBtn, tlp);
        bar.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(bar);

        ramLabel = new TextView(this);
        ramLabel.setTextColor(Color.parseColor("#8A8A8A"));
        ramLabel.setTextSize(11);
        ramLabel.setPadding(dp(12), 0, dp(12), dp(3));
        root.addView(ramLabel);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(Color.parseColor("#0084FF")));
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        homeView = buildHome();
        setContentView(root);
    }

    private View buildHome() {
        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(28), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("Lite Browser");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("Rapid și cu consum mic de RAM");
        sub.setTextColor(Color.parseColor("#8A8A8A"));
        sub.setTextSize(13);
        col.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dp(24), 0, dp(8));
        for (final String[] s : SITES) {
            TextView tile = new TextView(this);
            tile.setText(s[0]);
            tile.setTextColor(Color.WHITE);
            tile.setTextSize(15);
            tile.setTypeface(Typeface.DEFAULT_BOLD);
            tile.setGravity(Gravity.CENTER);
            boolean dark = s[0].equals("TikTok");
            tile.setBackground(round(s[2], 18, dark ? "#25F4EE" : null));
            tile.setOnClickListener(v -> openSite(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(100), 1f);
            lp.setMargins(dp(5), 0, dp(5), 0);
            row.addView(tile, lp);
        }
        col.addView(row, rowLp);

        TextView tabsTitle = new TextView(this);
        tabsTitle.setText("Taburi deschise");
        tabsTitle.setTextColor(Color.WHITE);
        tabsTitle.setTextSize(16);
        tabsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        tabsTitle.setPadding(dp(4), dp(24), 0, dp(8));
        col.addView(tabsTitle);

        homeTabsList = new LinearLayout(this);
        homeTabsList.setOrientation(LinearLayout.VERTICAL);
        col.addView(homeTabsList);

        sv.addView(col);
        return sv;
    }

    private void refreshHomeTabs() {
        if (homeTabsList == null) return;
        homeTabsList.removeAllViews();
        if (tabs.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("Niciun tab deschis");
            none.setTextColor(Color.GRAY);
            none.setPadding(dp(4), 0, 0, 0);
            homeTabsList.addView(none);
            return;
        }
        for (final Tab t : tabs) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(round("#1E1E1E", 12, null));
            r.setPadding(dp(14), 0, dp(4), 0);

            TextView label = new TextView(this);
            label.setText((t.wv != null ? "● " : "○ ") + label(t));
            label.setTextColor(t.wv != null ? Color.WHITE : Color.parseColor("#9A9A9A"));
            label.setSingleLine(true);
            label.setTextSize(14);
            label.setOnClickListener(v -> switchTo(t));

            TextView close = new TextView(this);
            close.setText("✕");
            close.setTextColor(Color.parseColor("#9A9A9A"));
            close.setTextSize(16);
            close.setGravity(Gravity.CENTER);
            close.setOnClickListener(v -> closeTab(t));

            r.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));
            label.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(close, new LinearLayout.LayoutParams(dp(44), dp(48)));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(6));
            homeTabsList.addView(r, lp);
        }
    }

    // ------------------------------------------------------------------ taburi

    private void openSite(String[] s) {
        String host = Uri.parse(s[1]).getHost();
        // daca site-ul e deja deschis, mergem la tabul lui (nu deschidem unul nou => mai putin RAM)
        for (Tab t : tabs) {
            String h = Uri.parse(t.url).getHost();
            if (h != null && host != null && sameSite(h, host)) {
                switchTo(t);
                return;
            }
        }
        newTab(s[1], "1".equals(s[3]));
    }

    private boolean sameSite(String a, String b) {
        return strip(a).equals(strip(b));
    }

    private String strip(String h) {
        if (h.startsWith("www.")) return h.substring(4);
        if (h.startsWith("m.")) return h.substring(2);
        return h;
    }

    private void newTab(String url, boolean desktop) {
        Tab t = new Tab();
        t.url = url;
        t.desktop = desktop;
        tabs.add(t);
        switchTo(t);
        saveTabs();
    }

    private void switchTo(Tab t) {
        hideKeyboard();
        if (current != null && current != t && current.wv != null) current.wv.onPause();
        current = t;
        t.lastUsed = SystemClock.elapsedRealtime();
        if (t.wv == null) {
            t.wv = createWebView(t);
            t.wv.loadUrl(t.url);
        } else {
            t.wv.onResume();
        }
        content.removeAllViews();
        if (t.wv.getParent() != null) ((ViewGroup) t.wv.getParent()).removeView(t.wv);
        content.addView(t.wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        urlBar.setText(t.url);
        enforceLimit();
        updateTabsBtn();
    }

    private void showHome() {
        hideKeyboard();
        if (current != null && current.wv != null) current.wv.onPause();
        current = null;
        content.removeAllViews();
        content.addView(homeView);
        urlBar.setText("");
        progress.setVisibility(View.INVISIBLE);
        refreshHomeTabs();
        updateTabsBtn();
    }

    private void closeTab(Tab t) {
        boolean wasCurrent = (t == current);
        sleep(t);
        tabs.remove(t);
        if (wasCurrent) {
            current = null;
            if (!tabs.isEmpty()) switchTo(tabs.get(tabs.size() - 1));
            else showHome();
        } else if (current == null) {
            refreshHomeTabs();
        }
        updateTabsBtn();
        saveTabs();
    }

    private void closeAll() {
        for (Tab t : tabs) sleep(t);
        tabs.clear();
        current = null;
        showHome();
        saveTabs();
    }

    /** Elibereaza memoria unui tab: pastreaza doar adresa. */
    private void sleep(Tab t) {
        if (t.wv == null) return;
        String u = t.wv.getUrl();
        if (u != null && !u.startsWith("about:")) t.url = u;
        WebView w = t.wv;
        t.wv = null;
        if (w.getParent() != null) ((ViewGroup) w.getParent()).removeView(w);
        w.stopLoading();
        w.destroy();
    }

    private void sleepBackgroundTabs() {
        boolean changed = false;
        for (Tab t : tabs) {
            if (t != current && t.wv != null) {
                sleep(t);
                changed = true;
            }
        }
        if (changed) {
            updateTabsBtn();
            if (current == null) refreshHomeTabs();
        }
    }

    private int liveCount() {
        int n = 0;
        for (Tab t : tabs) if (t.wv != null) n++;
        return n;
    }

    private void enforceLimit() {
        while (liveCount() > maxLive) {
            Tab oldest = null;
            for (Tab x : tabs) {
                if (x.wv != null && x != current && (oldest == null || x.lastUsed < oldest.lastUsed)) oldest = x;
            }
            if (oldest == null) break;
            sleep(oldest);
        }
    }

    private WebView anyLiveWebView() {
        for (Tab t : tabs) if (t.wv != null) return t.wv;
        return null;
    }

    private String label(Tab t) {
        if (t.title != null && !t.title.isEmpty() && !t.title.startsWith("http")) return t.title;
        String h = Uri.parse(t.url).getHost();
        return h != null ? h : t.url;
    }

    private void updateTabsBtn() {
        tabsBtn.setText(String.valueOf(tabs.size()));
    }

    // ------------------------------------------------------------------ WebView

    private WebView createWebView(final Tab t) {
        final WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // necesar pentru login-uri (Instagram, TikTok)
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportMultipleWindows(false);    // linkurile "_blank" se deschid in acelasi tab
        s.setGeolocationEnabled(false);
        s.setAllowFileAccess(false);
        s.setOffscreenPreRaster(false);
        s.setUserAgentString(t.desktop ? desktopUA : mobileUA);

        // Login-urile raman salvate: cookie-uri permanente, inclusiv cele de la terti (login Facebook).
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);

        // Tabul ascuns are prioritate mica: Android il elibereaza primul daca lipseste RAM.
        if (Build.VERSION.SDK_INT >= 26) {
            wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        }
        wv.setBackgroundColor(Color.parseColor(BG));

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme();
                if ("http".equals(sc) || "https".equals(sc)) return false;
                // "Deschide in aplicatie": nu deschidem aplicatiile grele, ramanem in browser
                if ("intent".equals(sc)) {
                    try {
                        Intent in = Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME);
                        String fb = in.getStringExtra("browser_fallback_url");
                        if (fb != null) v.loadUrl(fb);
                    } catch (Exception ignored) {
                    }
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                t.url = url;
                if (t == current && !urlBar.hasFocus()) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && !url.startsWith("about:")) t.url = url;
                String title = v.getTitle();
                if (title != null) t.title = title;
                if (t == current && !urlBar.hasFocus()) urlBar.setText(url);
                CookieManager.getInstance().flush();   // salveaza login-ul imediat
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail detail) {
                // Android a eliberat motorul web (lipsa RAM). Nu crapam: adormim tabul
                // si il reincarcam daca era cel vizibil.
                if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
                v.destroy();
                if (t.wv == v) t.wv = null;
                if (t == current) {
                    handler.post(() -> {
                        if (current == t && t.wv == null) switchTo(t);
                    });
                }
                updateTabsBtn();
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) {
                if (t != current) return;
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView v, String title) {
                if (title != null) t.title = title;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), FILE_REQ);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = cb;
                ((FrameLayout) getWindow().getDecorView()).addView(view,
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT));
                setFullscreen(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.deny();   // fara camera/microfon/locatie
            }
        });

        wv.setDownloadListener((url, ua, contentDisposition, mime, length) -> {
            try {
                DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url));
                String name = URLUtil.guessFileName(url, contentDisposition, mime);
                rq.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                rq.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null) rq.addRequestHeader("Cookie", cookie);
                rq.addRequestHeader("User-Agent", ua);
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(rq);
                toast("Se descarcă: " + name);
            } catch (Exception e) {
                toast("Fișierul nu poate fi descărcat");
            }
        });
        return wv;
    }

    private void hideCustomView() {
        if (customView == null) return;
        ((FrameLayout) getWindow().getDecorView()).removeView(customView);
        customView = null;
        setFullscreen(false);
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
    }

    @SuppressWarnings("deprecation")
    private void setFullscreen(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    // ------------------------------------------------------------------ navigare

    private void go(String q) {
        q = q.trim();
        if (q.isEmpty()) return;
        String url;
        if (q.startsWith("http://") || q.startsWith("https://")) url = q;
        else if (!q.contains(" ") && q.contains(".")) url = "https://" + q;
        else url = "https://www.google.com/search?q=" + Uri.encode(q);
        hideKeyboard();
        urlBar.clearFocus();
        if (current == null) {
            newTab(url, false);
        } else {
            current.url = url;
            if (current.wv == null) switchTo(current);
            else current.wv.loadUrl(url);
        }
    }

    private void handleIntent(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return;
        String u = i.getData().toString();
        if (u.startsWith("http://") || u.startsWith("https://")) newTab(u, false);
    }

    // ------------------------------------------------------------------ meniuri

    private void showTabs() {
        if (tabs.isEmpty()) {
            toast("Niciun tab deschis");
            return;
        }
        String[] items = new String[tabs.size()];
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            String state = t == current ? "  (curent)" : (t.wv == null ? "  (adormit)" : "");
            items[i] = (t.wv != null ? "● " : "○ ") + label(t) + state;
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Taburi: " + liveCount() + "/" + maxLive + " active  (ține apăsat ca să închizi)")
                .setItems(items, (dlg, which) -> switchTo(tabs.get(which)))
                .setPositiveButton("Tab nou", (dlg, w) -> showHome())
                .setNegativeButton("Închide toate", (dlg, w) -> closeAll());
        final AlertDialog d = b.create();
        d.show();
        d.getListView().setOnItemLongClickListener((parent, view, pos, id) -> {
            closeTab(tabs.get(pos));
            d.dismiss();
            return true;
        });
    }

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        if (current != null) {
            m.add(0, 1, 0, "Reîncarcă");
            m.add(0, 2, 0, "Mod desktop").setCheckable(true).setChecked(current.desktop);
            m.add(0, 3, 0, "Închide tabul");
        }
        m.add(0, 4, 0, "Rulează cu ecranul stins").setCheckable(true).setChecked(keepAlive);
        Menu sub = m.addSubMenu(0, 5, 0, "Taburi active maxim: " + maxLive);
        for (int n = 1; n <= 4; n++) sub.add(1, 100 + n, 0, n + (n == 1 ? " tab" : " taburi"));
        m.add(0, 6, 0, "Golește cache (păstrează login-urile)");
        m.add(0, 7, 0, "Despre consumul de RAM");
        m.add(0, 8, 0, "Ieșire completă");
        pm.setOnMenuItemClickListener(this::onMenu);
        pm.show();
    }

    private boolean onMenu(MenuItem item) {
        int id = item.getItemId();
        if (id == 5) return false;   // deschide submeniul
        if (id == 1 && current != null) {
            if (current.wv != null) current.wv.reload();
            else switchTo(current);
        } else if (id == 2 && current != null) {
            current.desktop = !current.desktop;
            if (current.wv != null) {
                current.wv.getSettings().setUserAgentString(current.desktop ? desktopUA : mobileUA);
                current.wv.reload();
            }
            saveTabs();
        } else if (id == 3 && current != null) {
            closeTab(current);
        } else if (id == 4) {
            keepAlive = !keepAlive;
            prefs.edit().putBoolean("keepAlive", keepAlive).apply();
            if (keepAlive) {
                startKeepAlive();
                toast("Paginile rămân active cu ecranul stins");
            } else {
                stopService(new Intent(this, KeepAliveService.class));
                toast("Oprit");
            }
        } else if (id > 100 && id <= 104) {
            maxLive = id - 100;
            prefs.edit().putInt("maxLive", maxLive).apply();
            enforceLimit();
            updateTabsBtn();
            if (current == null) refreshHomeTabs();
            toast("Maxim " + maxLive + " taburi active");
        } else if (id == 6) {
            // Sterge doar fisierele temporare. Cookie-urile si datele site-urilor
            // (adica login-urile) NU se ating.
            WebView w = anyLiveWebView();
            if (w != null) {
                w.clearCache(true);
            } else {
                WebView tmp = new WebView(this);
                tmp.clearCache(true);
                tmp.destroy();
            }
            toast("Cache golit. Login-urile au rămas salvate.");
        } else if (id == 7) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Consumul de RAM")
                    .setMessage("Doar tabul pe care îl vezi rulează. Celelalte sunt puse pe pauză, "
                            + "iar peste limita de taburi active sunt „adormite”: se păstrează doar "
                            + "adresa, iar pagina se reîncarcă când revii la ea.\n\n"
                            + "Când ieși din aplicație sau telefonul rămâne fără RAM, taburile din fundal "
                            + "sunt adormite automat.\n\n"
                            + "„Aplicație” din bara de sus arată memoria aplicației. Motorul web al "
                            + "paginilor (din Android) rulează separat și nu poate fi măsurat din aplicație.\n\n"
                            + "Login-urile rămân salvate tot timpul, chiar dacă un tab adoarme.")
                    .setPositiveButton("OK", null)
                    .show();
        } else if (id == 8) {
            stopService(new Intent(this, KeepAliveService.class));
            keepAlive = false;
            prefs.edit().putBoolean("keepAlive", false).apply();
            saveTabs();
            CookieManager.getInstance().flush();
            finishAndRemoveTask();
            handler.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 400);
        }
        return true;
    }

    private void startKeepAlive() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        try {
            startForegroundService(new Intent(this, KeepAliveService.class));
        } catch (Exception e) {
            toast("Nu am putut porni rularea în fundal");
        }
    }

    // ------------------------------------------------------------------ RAM

    private final Runnable ramTicker = new Runnable() {
        @Override
        public void run() {
            Debug.MemoryInfo mi = new Debug.MemoryInfo();
            Debug.getMemoryInfo(mi);
            int appMb = mi.getTotalPss() / 1024;

            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo sys = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(sys);
            long freeMb = sys.availMem / (1024 * 1024);

            ramLabel.setText("Aplicație: " + appMb + " MB  •  Taburi active: " + liveCount() + "/" + maxLive
                    + "  •  RAM liber: " + freeMb + " MB");

            // Telefonul ramane fara RAM: adormim taburile din fundal inainte sa inceapa lag-ul
            if (sys.lowMemory || freeMb < MIN_FREE_PHONE_MB) sleepBackgroundTabs();

            handler.postDelayed(this, 5000);
        }
    };

    // ------------------------------------------------------------------ salvare taburi

    private void saveTabs() {
        try {
            JSONArray arr = new JSONArray();
            for (Tab t : tabs) {
                String u = t.url;
                if (t.wv != null && t.wv.getUrl() != null && !t.wv.getUrl().startsWith("about:")) u = t.wv.getUrl();
                JSONObject o = new JSONObject();
                o.put("u", u);
                o.put("t", t.title == null ? "" : t.title);
                o.put("d", t.desktop);
                arr.put(o);
            }
            prefs.edit().putString("tabs", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreTabs() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("tabs", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Tab t = new Tab();
                t.url = o.getString("u");
                t.title = o.optString("t", "");
                t.desktop = o.optBoolean("d", false);
                tabs.add(t);   // pornesc adormite: zero RAM pana le deschizi
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ utilitare

    private static String chromeVersion(String ua) {
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(ua);
        return m.find() ? m.group(1) : "130.0.0.0";
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && urlBar != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
