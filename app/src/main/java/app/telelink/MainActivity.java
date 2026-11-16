package app.telelink;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
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
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final String HOME = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>body{background:#12151c;color:#dfe3ea;font-family:sans-serif;padding:24px;line-height:1.5}"
            + "h2{color:#3ddc97}b{color:#fff}</style></head><body><h2>TeleLink</h2>"
            + "<p>1. Escribe arriba la web o comparte un enlace desde otra app.</p>"
            + "<p>2. Dale al play del vídeo en la página.</p>"
            + "<p>3. Pulsa el botón verde <b>Enviar a la tele</b>. Si la tele no arranca, "
            + "la app prueba sola el modo compatible y lo recuerda para esa web.</p>"
            + "<p><b>Vídeos</b> sirve para elegir a mano otro vídeo u otro modo.</p>"
            + "<p><b>☰</b> tiene favoritos e historial. La app recuerda por dónde ibas en cada vídeo.</p>"
            + "<p>El candado se pone solo al cargar la página y bloquea los saltos a otras webs. "
            + "Si un anuncio se come el toque, vuelve a pulsar el play. "
            + "Quítalo si necesitas pasar a otra web.</p>"
            + "<p>Móvil y tele tienen que estar en la misma wifi.</p></body></html>";

    private WebView web;
    private EditText urlBar;
    private ProgressBar progress;
    private Button btnVideos;
    private Button btnLock;
    private Button btnSend;
    private volatile int sendSeq;

    /** Lo último que se mandó a la tele: hace falta para saltar y para recordar por dónde ibas. */
    private static final class Session {
        Dlna.Device target;
        String id, base, how, title, pageKey;
        volatile int offset; // segundo en el que empieza el flujo actual (solo modo compatible)
    }

    private volatile Session session; // cada envío nuevo (o un "Parar") cancela la vigilancia del anterior

    private final List<String> videos = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Dlna.Device tv;
    private volatile String lockedDomain; // null = candado quitado
    private volatile String pageTitle = "Vídeo";
    private final Map<String, Map<String, String>> videoHeaders = new ConcurrentHashMap<>();
    private volatile String currentPage = "";
    private String userAgent = "";
    private static final int AUTO = 0, COMPAT = 1, DIRECT = 2;
    private static final String RELEASE = "https://github.com/Cimentacion/Telelink/releases/download/latest/";
    private static final String INSTALL = "app.telelink.INSTALL";
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
        checkUpdate();
    }

    // ---------- Actualización automática ----------

    private static HttpURLConnection get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        return c;
    }

    /** Al abrir la app mira si hay una compilación más nueva en GitHub. */
    private void checkUpdate() {
        new Thread(() -> {
            try {
                int mine = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
                HttpURLConnection c = get(RELEASE + "version.txt");
                if (c.getResponseCode() != 200) return;
                byte[] buf = new byte[32];
                InputStream in = c.getInputStream();
                int n = in.read(buf);
                in.close();
                if (n <= 0) return;
                final int remote = Integer.parseInt(new String(buf, 0, n, "UTF-8").trim());
                if (remote <= mine) return;
                ui.post(() -> {
                    if (isFinishing()) return;
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Hay una versión nueva")
                            .setMessage("Tienes la " + mine + " y está disponible la " + remote + ".")
                            .setPositiveButton("Actualizar", (d, w) -> downloadUpdate())
                            .setNegativeButton("Ahora no", null)
                            .show();
                });
            } catch (Exception ignored) {
                // Sin conexión o sin versión publicada: se vuelve a mirar la próxima vez.
            }
        }, "telelink-update").start();
    }

    private void downloadUpdate() {
        toast("Descargando la actualización…");
        new Thread(() -> {
            try {
                HttpURLConnection c = get(RELEASE + "TeleLink.apk");
                if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int id = installer.createSession(params);
                PackageInstaller.Session session = installer.openSession(id);
                try {
                    OutputStream out = session.openWrite("telelink.apk", 0, -1);
                    InputStream in = c.getInputStream();
                    byte[] buf = new byte[65536];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    session.fsync(out);
                    out.close();
                    in.close();

                    Intent back = new Intent(MainActivity.this, MainActivity.class).setAction(INSTALL);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT
                            | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pi = PendingIntent.getActivity(MainActivity.this, 2, back, flags);
                    session.commit(pi.getIntentSender());
                } finally {
                    session.close();
                }
            } catch (final Exception e) {
                ui.post(() -> toast("No se pudo actualizar: " + e.getMessage()));
            }
        }, "telelink-download").start();
    }

    /** Android devuelve aquí el resultado de la instalación; lo normal es que pida confirmación. */
    private void onInstallResult(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm);
                } catch (Exception e) {
                    toast("No se pudo abrir el instalador");
                }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            toast("Actualización cancelada" + (msg == null ? "" : ": " + msg));
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

        btnSend = new Button(this);
        btnSend.setAllCaps(false);
        btnSend.setTextSize(17);
        btnSend.setTextColor(Color.parseColor("#0d1117"));
        btnSend.setBackgroundColor(Color.parseColor("#3ddc97"));
        btnSend.setVisibility(View.GONE);
        btnSend.setOnClickListener(v -> quickSend());
        root.addView(btnSend, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));

        // Barra inferior
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(4), 0, dp(4), 0);

        Button back = bar("◀");
        back.setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
        });
        bottom.addView(back, new LinearLayout.LayoutParams(0, dp(50), 1));

        Button menu = bar("☰");
        menu.setOnClickListener(v -> showMenu());
        bottom.addView(menu, new LinearLayout.LayoutParams(0, dp(50), 1));

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
        btnSend.setVisibility(videos.isEmpty() ? View.GONE : View.VISIBLE);
        if (videos.isEmpty() || !sending) sendLabel();
    }

    private volatile boolean sending;

    private void sendLabel() {
        btnSend.setText(tv == null ? "▶  Enviar a la tele" : "▶  Enviar a " + tv.name);
    }

    /** Texto de progreso en el botón grande (se puede llamar desde cualquier hilo). */
    private void status(final String text) {
        ui.post(() -> btnSend.setText(text));
    }

    /** El mejor candidato: la lista HLS principal si la hay; si no, el último archivo de vídeo. */
    private String bestVideo() {
        String firstHls = null, lastFile = null;
        for (String v : videos) {
            String p = Uri.parse(v).getPath();
            p = p == null ? "" : p.toLowerCase(Locale.US);
            if (p.endsWith(".m3u8")) {
                if (p.contains("master")) return v;
                if (firstHls == null) firstHls = v;
            } else {
                lastFile = v;
            }
        }
        return firstHls != null ? firstHls : lastFile;
    }

    private void quickSend() {
        String url = bestVideo();
        if (url == null) {
            toast("Dale primero al play en la página");
            return;
        }
        send(url, AUTO);
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
        // Quita la marca de WebView del User-Agent: hay reproductores que se niegan si la ven.
        userAgent = s.getUserAgentString().replace("; wv", "").replaceAll("Version/\\d+\\.\\d+ ", "");
        s.setUserAgentString(userAgent);

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
                if (isVideo(u)) {
                    Map<String, String> h = request.getRequestHeaders();
                    videoHeaders.put(u.toString(), h == null ? new HashMap<>() : new HashMap<>(h));
                    addVideo(u.toString());
                }
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
                if (url != null && url.startsWith("http")) {
                    urlBar.setText(url);
                    currentPage = url;
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("http")) addHistory(view.getTitle(), url);
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
        videoHeaders.clear();
        refreshVideos();
        hideKeyboard();
        web.loadUrl(url);
    }

    private boolean handleIntent(Intent intent) {
        if (intent == null) return false;
        if (INSTALL.equals(intent.getAction())) {
            onInstallResult(intent);
            return false;
        }
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
                .setItems(items, (d, which) -> chooseMode(snapshot.get(which)))
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
                            if (!sending) sendLabel();
                            if (then != null) then.run();
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            });
        });
    }

    private void chooseMode(final String url) {
        String[] modes = {
                "Automático (recomendado)",
                "Modo compatible\nSi la tele da error o se queda en negro",
                "Directo\nSin pasar por el móvil"
        };
        new AlertDialog.Builder(this)
                .setTitle("Cómo enviarlo")
                .setItems(modes, (d, which) -> send(url, which))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Cabeceras con las que el navegador pidió el vídeo; el puente las repite ante la web. */
    private Map<String, String> headersFor(String url) {
        Map<String, String> h = new HashMap<>();
        Map<String, String> seen = videoHeaders.get(url);
        if (seen != null) h.putAll(seen);
        boolean referer = false, agent = false;
        for (String k : h.keySet()) {
            if (k.equalsIgnoreCase("Referer")) referer = true;
            if (k.equalsIgnoreCase("User-Agent")) agent = true;
        }
        if (!referer && currentPage.startsWith("http")) h.put("Referer", currentPage);
        if (!agent && !userAgent.isEmpty()) h.put("User-Agent", userAgent);
        return h;
    }

    private void startBridgeService() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        Intent i = new Intent(this, BridgeService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {
        }
    }

    private void stopBridgeService() {
        try {
            startService(new Intent(this, BridgeService.class).setAction(BridgeService.STOP));
        } catch (Exception ignored) {
            Bridge.stop();
        }
    }

    private String siteKey() {
        try {
            String host = Uri.parse(currentPage).getHost();
            return host == null ? "" : "mode_" + baseDomain(host);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Vigila la tele unos segundos tras enviar. true = está reproduciendo; false = se ha quedado
     * parada o en negro. Si la tele no informa de su estado, se da por bueno.
     */
    private boolean watch(Dlna.Device target, int my) {
        int playing = 0, dead = 0, unknown = 0;
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return true;
            }
            if (my != sendSeq) return true;
            String st;
            int pos = 0;
            try {
                st = Dlna.state(this, target);
                try {
                    pos = Dlna.position(this, target);
                } catch (IOException ignored) {
                }
            } catch (IOException e) {
                unknown++;
                continue;
            }
            if (st.equals("PLAYING") || st.equals("PAUSED_PLAYBACK")) {
                playing++;
                dead = 0;
                if (pos > 0 || playing >= 3) return true;
            } else if (st.equals("STOPPED") || st.equals("NO_MEDIA_PRESENT")) {
                playing = 0;
                if (i >= 1 && ++dead >= 2) return false;
            } else {
                playing = 0;
            }
        }
        return unknown >= 8;
    }

    private static String clock(int secs) {
        int h = secs / 3600, m = (secs / 60) % 60, sec = secs % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    /** Envía; si de esta página hay un punto guardado, pregunta si seguir desde ahí. */
    private void send(final String url, final int mode) {
        if (tv == null) {
            pickTv(() -> send(url, mode));
            return;
        }
        final int saved = getSharedPreferences("resume", MODE_PRIVATE).getInt(currentPage, 0);
        if (saved < 60) {
            send(url, mode, 0);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Ya habías empezado este vídeo")
                .setPositiveButton("Seguir desde " + clock(saved), (d, w) -> send(url, mode, saved))
                .setNegativeButton("Desde el principio", (d, w) -> send(url, mode, 0))
                .show();
    }

    private void send(final String url, final int mode, final int startAt) {
        if (tv == null) {
            pickTv(() -> send(url, mode, startAt));
            return;
        }
        final Dlna.Device target = tv;
        final String title = pageTitle;
        final String pageKey = currentPage;
        final Map<String, String> headers = headersFor(url);
        final String site = siteKey();
        final SharedPreferences modes = getSharedPreferences("modes", MODE_PRIVATE);
        final int my = ++sendSeq;
        if (mode != DIRECT) startBridgeService();
        sending = true;
        status("Enviando a " + target.name + "…");

        new Thread(() -> {
            try {
                final Session ses = new Session();
                ses.target = target;
                ses.title = title;
                ses.pageKey = pageKey;
                if (mode == DIRECT) {
                    Dlna.play(MainActivity.this, target, url, title, null);
                    ses.how = "directo";
                } else {
                    String ip = Dlna.wifiIp(MainActivity.this);
                    if (ip == null) throw new IOException("El móvil no está en la wifi.");
                    int port = Bridge.start();
                    String id = Bridge.register(url, headers);
                    Bridge.Probe p = Bridge.probe(id);
                    if (p.code >= 400) {
                        throw new IOException("La web no entrega este vídeo (HTTP " + p.code + "). "
                                + "Recarga la página, dale al play otra vez y vuelve a enviarlo.");
                    }
                    String base = "http://" + ip + ":" + port;
                    ses.id = id;
                    ses.base = base;
                    if (!p.hls) {
                        String mime = p.type != null && p.type.startsWith("video/") ? p.type : "video/mp4";
                        Dlna.play(MainActivity.this, target, base + "/p/" + id + "/video.mp4", title, mime);
                        ses.how = "puente";
                    } else {
                        // Si con esta web ya hizo falta el modo compatible, se va directo a él.
                        boolean compatFirst = mode == COMPAT
                                || (mode == AUTO && modes.getInt(site, AUTO) == COMPAT);
                        boolean ok = false;
                        if (!compatFirst) {
                            try {
                                Dlna.play(MainActivity.this, target, base + "/p/" + id + "/index.m3u8", title,
                                        "application/vnd.apple.mpegurl");
                                status("Comprobando que la tele reproduce…");
                                ok = watch(target, my);
                            } catch (IOException rejected) {
                                ok = false;
                            }
                            if (my != sendSeq) return;
                        }
                        if (ok) {
                            ses.how = "puente";
                            if (!site.isEmpty()) modes.edit().putInt(site, AUTO).apply();
                        } else {
                            if (!compatFirst) status("No arranca. Probando modo compatible…");
                            Dlna.play(MainActivity.this, target,
                                    base + "/ts/" + id + "/" + startAt + "/stream.ts", title, "video/mpeg");
                            if (compatFirst) status("Comprobando que la tele reproduce…");
                            ok = watch(target, my);
                            if (my != sendSeq) return;
                            if (!ok) {
                                String err = Bridge.lastError;
                                throw new IOException("La tele no ha llegado a reproducir el vídeo."
                                        + (err == null || err.isEmpty() ? "" : "\n\n" + err));
                            }
                            ses.how = "compatible";
                            ses.offset = startAt;
                            if (!site.isEmpty()) modes.edit().putInt(site, COMPAT).apply();
                        }
                    }
                }
                // En los modos en que la tele sabe saltar, se coloca en el punto guardado.
                if (startAt > 0 && !"compatible".equals(ses.how)) {
                    try {
                        Thread.sleep(1500);
                        Dlna.seekTo(MainActivity.this, target, startAt);
                    } catch (Exception ignored) {
                    }
                }
                if (my != sendSeq) return;
                session = ses;
                track(ses, my);
                ui.post(() -> {
                    if (my != sendSeq) return;
                    sending = false;
                    sendLabel();
                    toast("Reproduciendo en " + target.name + " (" + ses.how + ")");
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    if (my != sendSeq) return;
                    sending = false;
                    sendLabel();
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("No se ha podido enviar")
                            .setMessage(String.valueOf(e.getMessage()))
                            .setPositiveButton("Modo compatible", (d, w) -> send(url, COMPAT, startAt))
                            .setNeutralButton("Buscar tele", (d, w) -> pickTv(() -> send(url, mode, startAt)))
                            .setNegativeButton("Cerrar", null)
                            .show();
                });
            }
        }, "telelink-send").start();
    }

    /** Mientras se reproduce, guarda cada 15 s por dónde va para poder retomarlo otro día. */
    private void track(final Session ses, final int my) {
        new Thread(() -> {
            final SharedPreferences resume = getSharedPreferences("resume", MODE_PRIVATE);
            int stopped = 0;
            while (my == sendSeq && stopped < 4) {
                try {
                    Thread.sleep(15000);
                } catch (InterruptedException e) {
                    return;
                }
                if (my != sendSeq) return;
                try {
                    String st = Dlna.state(MainActivity.this, ses.target);
                    if (st.equals("PLAYING") || st.equals("PAUSED_PLAYBACK")) {
                        stopped = 0;
                        int pos = Dlna.position(MainActivity.this, ses.target) + ses.offset;
                        if (pos >= 60 && ses.pageKey != null && ses.pageKey.startsWith("http")) {
                            resume.edit().putInt(ses.pageKey, pos).apply();
                        }
                    } else if (st.equals("STOPPED") || st.equals("NO_MEDIA_PRESENT")) {
                        stopped++;
                    }
                } catch (IOException ignored) {
                }
            }
        }, "telelink-track").start();
    }

    /** Salto relativo. En modo compatible la tele no sabe saltar: se reinicia el flujo en el punto nuevo. */
    private String jump(Dlna.Device d, int delta) throws Exception {
        Session ses = session;
        if (ses != null && "compatible".equals(ses.how) && ses.target == d) {
            int now = ses.offset + Dlna.position(this, d);
            int to = Math.max(0, now + delta);
            Dlna.play(this, d, ses.base + "/ts/" + ses.id + "/" + to + "/stream.ts", ses.title, "video/mpeg");
            ses.offset = to;
            return "Saltando a " + clock(to);
        }
        Dlna.seekBy(this, d, delta);
        return null;
    }

    // ---------- Menú, favoritos e historial ----------

    private JSONArray loadList(String key) {
        try {
            return new JSONArray(getSharedPreferences("lists", MODE_PRIVATE).getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void storeList(String key, JSONArray a) {
        getSharedPreferences("lists", MODE_PRIVATE).edit().putString(key, a.toString()).apply();
    }

    private int indexOf(JSONArray a, String url) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && url.equals(o.optString("u"))) return i;
        }
        return -1;
    }

    /** Pone la página la primera de la lista (sin repetirla) y recorta a un máximo. */
    private void putFirst(String key, String title, String url, int max) {
        try {
            JSONArray old = loadList(key), out = new JSONArray();
            JSONObject e = new JSONObject();
            e.put("t", title == null || title.isEmpty() ? url : title);
            e.put("u", url);
            out.put(e);
            for (int i = 0; i < old.length() && out.length() < max; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !url.equals(o.optString("u"))) out.put(o);
            }
            storeList(key, out);
        } catch (Exception ignored) {
        }
    }

    private void addHistory(String title, String url) {
        putFirst("history", title, url, 60);
    }

    private void showMenu() {
        final String url = web.getUrl();
        final boolean page = url != null && url.startsWith("http");
        final boolean fav = page && indexOf(loadList("favorites"), url) >= 0;
        String[] items = {
                "Recargar",
                fav ? "★ Quitar de favoritos" : "☆ Añadir a favoritos",
                "Favoritos",
                "Historial"
        };
        new AlertDialog.Builder(this)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        web.reload();
                    } else if (which == 1) {
                        if (!page) {
                            toast("Abre primero una web");
                        } else if (fav) {
                            JSONArray a = loadList("favorites");
                            int i = indexOf(a, url);
                            if (i >= 0) a.remove(i);
                            storeList("favorites", a);
                            toast("Quitado de favoritos");
                        } else {
                            putFirst("favorites", web.getTitle(), url, 200);
                            toast("Guardado en favoritos");
                        }
                    } else if (which == 2) {
                        showList("favorites", "Favoritos", false);
                    } else {
                        showList("history", "Historial", false);
                    }
                })
                .show();
    }

    /** Lista de páginas guardadas. En modo borrar, tocar una la quita en vez de abrirla. */
    private void showList(final String key, final String title, final boolean deleting) {
        final JSONArray a = loadList(key);
        if (a.length() == 0) {
            toast(title + ": no hay nada todavía");
            return;
        }
        final SharedPreferences resume = getSharedPreferences("resume", MODE_PRIVATE);
        String[] items = new String[a.length()];
        for (int i = 0; i < items.length; i++) {
            JSONObject o = a.optJSONObject(i);
            String u = o == null ? "" : o.optString("u");
            String t = o == null ? "" : o.optString("t");
            int pos = resume.getInt(u, 0);
            String host = Uri.parse(u).getHost();
            items[i] = t + "\n" + (host == null ? u : host) + (pos >= 60 ? "  ·  vas por " + clock(pos) : "");
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(deleting ? title + ": toca para quitar" : title)
                .setItems(items, (d, which) -> {
                    JSONObject o = a.optJSONObject(which);
                    if (o == null) return;
                    if (deleting) {
                        a.remove(which);
                        storeList(key, a);
                        if (a.length() > 0) showList(key, title, true);
                    } else {
                        go(o.optString("u"));
                    }
                })
                .setNegativeButton("Cerrar", null);
        if (deleting) {
            b.setNeutralButton("Borrar todo", (d, w) -> storeList(key, new JSONArray()));
        } else {
            b.setNeutralButton("Quitar…", (d, w) -> showList(key, title, true));
        }
        b.show();
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

        addRemote(r1, "−30 s", d -> jump(d, -30));
        addRemote(r1, "Pausa", d -> { Dlna.pause(this, d); return null; });
        addRemote(r1, "Play", d -> { Dlna.resume(this, d); return null; });
        addRemote(r1, "+30 s", d -> jump(d, 30));

        addRemote(r2, "Vol −", d -> "Volumen " + Dlna.volumeBy(this, d, -3));
        addRemote(r2, "Vol +", d -> "Volumen " + Dlna.volumeBy(this, d, 3));
        addRemote(r2, "−5 min", d -> jump(d, -300));
        addRemote(r2, "+5 min", d -> jump(d, 300));

        addRemote(r3, "Parar", d -> {
            sendSeq++;
            ui.post(() -> {
                sending = false;
                sendLabel();
                stopBridgeService();
            });
            Dlna.stop(this, d);
            return "Parado";
        });

        // Diagnóstico: si la tele no pide nada al móvil, el contador se queda en 0.
        TextView diag = new TextView(this);
        diag.setPadding(dp(4), dp(10), dp(4), 0);
        diag.setTextSize(12);
        String err = Bridge.lastError;
        diag.setText("Peticiones de la tele al móvil: " + Bridge.hits.get()
                + (err == null || err.isEmpty() ? "" : "\nÚltimo error: " + err));

        box.addView(r1);
        box.addView(r2);
        box.addView(r3);
        box.addView(diag);

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
