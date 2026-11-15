package app.telelink;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String HOME = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{background:#12151c;color:#dfe3ea;font-family:sans-serif;padding:24px;line-height:1.5}"
            + "h2{color:#3ddc97}b{color:#fff}</style></head><body><h2>TeleLink</h2>"
            + "<p>1. Escribe arriba la web o comparte un enlace desde otra app.</p>"
            + "<p>2. Dale al play del vídeo en la página.</p>"
            + "<p>3. Pulsa <b>Vídeos</b> y elige cuál mandar a la tele.</p>"
            + "<p>El candado se pone solo al cargar la página y bloquea los saltos a otras webs. "
            + "Si un anuncio se come el toque, vuelve a pulsar el play. "
            + "Quítalo si necesitas pasar a otra web.</p>"
            + "<p>Móvil y tele tienen que estar en la misma wifi.</p></body></html>";

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private Button btnVideos;
    private Button btnLock;

    private final List<String> videos = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Dlna.Device tv;
    private volatile String lockedDomain; // null = candado quitado
    private volatile String pageTitle = "Vídeo";
    private boolean autoLockPending; // activar el candado en cuanto cargue la página pedida

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        setupWeb();
        loadSavedTv();
        if (!handleIntent(getIntent())) {
            web.loadDataWithBaseURL(null, HOME, "text/html", "utf-8", null);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    // ---------- Interfaz ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button bar(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setPadding(dp(4), 0, dp(4), 0);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#12151c"));

        // Barra de dirección
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(6), dp(2), dp(6), dp(2));

        urlBar = new EditText(this);
        urlBar.setSingleLine(true);
        urlBar.setHint("Web o búsqueda");
        urlBar.setTextSize(15);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                go(urlBar.getText().toString());
                return true;
            }
            return false;
        });
        top.addView(urlBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button goBtn = bar("Ir");
        goBtn.setOnClickListener(v -> go(urlBar.getText().toString()));
        top.addView(goBtn, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // Barra inferior
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(4), 0, dp(4), 0);

        Button back = bar("◀");
        back.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
        });
        bottom.addView(back, new LinearLayout.LayoutParams(0, dp(50), 1));

        Button reload = bar("⟳");
        reload.setOnClickListener(v -> web.reload());
        bottom.addView(reload, new LinearLayout.LayoutParams(0, dp(50), 1));

        btnLock = bar("🔓");
        btnLock.setOnClickListener(v -> toggleLock());
        bottom.addView(btnLock, new LinearLayout.LayoutParams(0, dp(50), 1));

        btnVideos = bar("Vídeos (0)");
        btnVideos.setOnClickListener(v -> showVideos());
        bottom.addView(btnVideos, new LinearLayout.LayoutParams(0, dp(50), 2.4f));

        Button remote = bar("Mando");
        remote.setOnClickListener(v -> showRemote());
        bottom.addView(remote, new LinearLayout.LayoutParams(0, dp(50), 1.8f));

        root.addView(bottom);
        setContentView(root);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void refreshVideos() {
        btnVideos.setText("Vídeos (" + videos.size() + ")");
        btnVideos.setTextColor(videos.isEmpty() ? Color.LTGRAY : Color.parseColor("#3ddc97"));
    }

    // ---------- Navegador ----------

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Con ventanas múltiples activadas, los window.open pasan por onCreateWindow y ahí se bloquean.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int p) {
                progress.setProgress(p);
                progress.setVisibility(p < 100 ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                if (title != null && !title.isEmpty()) pageTitle = title;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message msg) {
                return false; // pop-up bloqueado
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (AdBlock.isAd(u.getHost())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                if (isVideo(u)) addVideo(u.toString());
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.US);
                if (!scheme.equals("http") && !scheme.equals("https")) return true; // intent://, market://...
                if (AdBlock.isAd(u.getHost())) return true;
                String lock = lockedDomain;
                if (lock != null && request.isForMainFrame() && !lock.equals(baseDomain(u.getHost()))) {
                    toast("Bloqueado salto a " + u.getHost());
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url != null && url.startsWith("http")) urlBar.setText(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!autoLockPending || url == null || !url.startsWith("http")) return;
                autoLockPending = false;
                String host = Uri.parse(url).getHost();
                if (host == null || isSearchEngine(host)) return;
                // Candado automático: a partir de aquí la página no puede llevarte a otra web.
                setLock(baseDomain(host));
            }
        });
    }

    private void go(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) return;
        String url;
        if (t.startsWith("http://") || t.startsWith("https://")) {
            url = t;
        } else if (t.contains(".") && !t.contains(" ")) {
            url = "https://" + t;
        } else {
            url = "https://duckduckgo.com/?q=" + Uri.encode(t);
        }
        // Navegación pedida por el usuario: el candado se quita para no bloquearla.
        setLock(null);
        autoLockPending = true;
        videos.clear();
        refreshVideos();
        hideKeyboard();
        web.loadUrl(url);
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        String url = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            url = intent.getData().toString();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Matcher m = Pattern.compile("https?://\\S+").matcher(text);
                if (m.find()) url = m.group();
            }
        }
        if (url == null) return false;
        go(url);
        return true;
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
    }

    private static boolean isSearchEngine(String host) {
        String d = baseDomain(host);
        return d.startsWith("duckduckgo.") || d.startsWith("google.") || d.startsWith("bing.");
    }

    private void toggleLock() {
        autoLockPending = false;
        if (lockedDomain != null) {
            setLock(null);
            toast("Candado quitado");
            return;
        }
        String url = web.getUrl();
        String host = url == null ? null : Uri.parse(url).getHost();
        if (host == null) {
            toast("Abre primero una web");
            return;
        }
        setLock(baseDomain(host));
        toast("Solo se permite navegar dentro de " + lockedDomain);
    }

    private void setLock(String domain) {
        lockedDomain = domain;
        btnLock.setText(domain == null ? "🔓" : "🔒");
    }

    /** Aproximación: las dos últimas partes del host (o tres si es tipo .co.uk / .com.es). */
    private static String baseDomain(String host) {
        if (host == null) return "";
        String[] p = host.toLowerCase(Locale.US).split("\\.");
        if (p.length <= 2) return host.toLowerCase(Locale.US);
        String second = p[p.length - 2];
        boolean compound = p[p.length - 1].length() == 2
                && (second.equals("co") || second.equals("com") || second.equals("org") || second.equals("net"));
        int n = compound ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - n; i < p.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(p[i]);
        }
        return sb.toString();
    }

    // ---------- Vídeos detectados ----------

    private static boolean isVideo(Uri u) {
        String p = u.getPath();
        if (p == null) return false;
        p = p.toLowerCase(Locale.US);
        return p.endsWith(".m3u8") || p.endsWith(".mp4") || p.endsWith(".webm")
                || p.endsWith(".mkv") || p.endsWith(".mov");
    }

    private void addVideo(final String url) {
        ui.post(() -> {
            if (!videos.contains(url)) {
                videos.add(url);
                refreshVideos();
            }
        });
    }

    private static String label(String url) {
        Uri u = Uri.parse(url);
        String name = u.getLastPathSegment();
        if (name == null) name = url;
        if (name.length() > 40) name = "…" + name.substring(name.length() - 40);
        String type = name.toLowerCase(Locale.US).endsWith(".m3u8") ? "HLS" : "Archivo";
        return type + " · " + name + "\n" + u.getHost();
    }

    private void showVideos() {
        if (videos.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Aún no hay vídeos")
                    .setMessage("Dale al play en la página y espera un par de segundos. "
                            + "Cuando el contador suba, vuelve aquí.")
                    .setPositiveButton("Vale", null)
                    .show();
            return;
        }
        final List<String> snapshot = new ArrayList<>(videos);
        String[] items = new String[snapshot.size()];
        for (int i = 0; i < items.length; i++) items[i] = label(snapshot.get(i));
        new AlertDialog.Builder(this)
                .setTitle("Mandar a la tele")
                .setItems(items, (d, which) -> send(snapshot.get(which)))
                .setNeutralButton("Vaciar lista", (d, w) -> {
                    videos.clear();
                    refreshVideos();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ---------- Tele ----------

    private void loadSavedTv() {
        SharedPreferences p = getSharedPreferences("tv", MODE_PRIVATE);
        String av = p.getString("av", null);
        if (av == null) return;
        tv = new Dlna.Device();
        tv.name = p.getString("name", "Tele");
        tv.avControl = av;
        tv.rcControl = p.getString("rc", null);
    }

    private void saveTv() {
        getSharedPreferences("tv", MODE_PRIVATE).edit()
                .putString("name", tv.name)
                .putString("av", tv.avControl)
                .putString("rc", tv.rcControl)
                .apply();
    }

    private void pickTv(final Runnable then) {
        if (!Dlna.hasWifi(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("El móvil no está en la wifi")
                    .setMessage("Ahora mismo vas por datos móviles. Conecta el móvil a la misma wifi "
                            + "que la tele y vuelve a intentarlo.")
                    .setPositiveButton("Reintentar", (d, w) -> pickTv(then))
                    .setNegativeButton("Cancelar", null)
                    .show();
            return;
        }
        final AlertDialog wait = new AlertDialog.Builder(this)
                .setMessage("Buscando teles en la wifi…")
                .setCancelable(false)
                .show();
        io.execute(() -> {
            final List<Dlna.Device> found = Dlna.discover(MainActivity.this, 4000);
            ui.post(() -> {
                wait.dismiss();
                if (found.isEmpty()) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("No encuentro ninguna tele")
                            .setMessage("Comprueba que la tele está encendida y en la misma wifi que el móvil.")
                            .setPositiveButton("Reintentar", (d, w) -> pickTv(then))
                            .setNegativeButton("Cancelar", null)
                            .show();
                    return;
                }
                String[] names = new String[found.size()];
                for (int i = 0; i < names.length; i++) names[i] = found.get(i).name;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Elige la tele")
                        .setItems(names, (d, which) -> {
                            tv = found.get(which);
                            saveTv();
                            if (then != null) then.run();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            });
        });
    }

    private void send(final String url) {
        if (tv == null) {
            pickTv(() -> send(url));
            return;
        }
        final Dlna.Device target = tv;
        final String title = pageTitle;
        toast("Enviando a " + target.name + "…");
        io.execute(() -> {
            try {
                Dlna.play(MainActivity.this, target, url, title);
                ui.post(() -> toast("Reproduciendo en " + target.name));
            } catch (final Exception e) {
                ui.post(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("La tele no lo ha aceptado")
                        .setMessage(e.getMessage()
                                + "\n\nPrueba con otro vídeo de la lista o busca la tele otra vez.")
                        .setPositiveButton("Buscar tele", (d, w) -> pickTv(() -> send(url)))
                        .setNegativeButton("Cerrar", null)
                        .show());
            }
        });
    }

    private interface TvAction {
        String run(Dlna.Device d) throws Exception;
    }

    private void control(final TvAction action) {
        if (tv == null) {
            pickTv(null);
            return;
        }
        final Dlna.Device target = tv;
        io.execute(() -> {
            try {
                final String msg = action.run(target);
                if (msg != null) ui.post(() -> toast(msg));
            } catch (final Exception e) {
                ui.post(() -> toast(String.valueOf(e.getMessage())));
            }
        });
    }

    private void showRemote() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), dp(8));

        LinearLayout r1 = new LinearLayout(this);
        LinearLayout r2 = new LinearLayout(this);
        LinearLayout r3 = new LinearLayout(this);

        addRemote(r1, "−30 s", d -> { Dlna.seekBy(this, d, -30); return null; });
        addRemote(r1, "Pausa", d -> { Dlna.pause(this, d); return null; });
        addRemote(r1, "Play", d -> { Dlna.resume(this, d); return null; });
        addRemote(r1, "+30 s", d -> { Dlna.seekBy(this, d, 30); return null; });

        addRemote(r2, "Vol −", d -> "Volumen " + Dlna.volumeBy(this, d, -3));
        addRemote(r2, "Vol +", d -> "Volumen " + Dlna.volumeBy(this, d, 3));
        addRemote(r2, "+5 min", d -> { Dlna.seekBy(this, d, 300); return null; });

        addRemote(r3, "Parar", d -> { Dlna.stop(this, d); return "Parado"; });

        box.addView(r1);
        box.addView(r2);
        box.addView(r3);

        new AlertDialog.Builder(this)
                .setTitle(tv == null ? "Mando (sin tele)" : "Mando · " + tv.name)
                .setView(box)
                .setNeutralButton("Cambiar de tele", (d, w) -> pickTv(null))
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private void addRemote(LinearLayout row, String text, final TvAction action) {
        Button b = bar(text);
        b.setOnClickListener(v -> control(action));
        row.addView(b, new LinearLayout.LayoutParams(0, dp(56), 1));
    }

    // ---------- Ciclo de vida ----------

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }
}
