package dev.deepgravity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.AssetManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Message;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * DeepGravity —— 极简地址容器。
 *
 * 地址完全由用户自己添加，保存在应用私有目录（filesDir/sites.json），
 * 不预置任何站点。首页由 assets/www/ 提供，通过虚拟源 https://app.local/ 供出。
 *
 * 没有任何第三方依赖，只用 android.jar 自带能力。
 */
public class MainActivity extends Activity {

    private static final String HOST = "app.local";
    private static final String HOME = "https://" + HOST + "/index.html";
    private static final String STORE_FILE = "sites.json";

    private WebView web;

    /** 允许跳过证书校验的主机：只来自用户给该条目勾选「允许自签名证书」。 */
    private final Set<String> trustedHosts = new HashSet<>();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        refreshTrustedHosts();

        web = new WebView(this);
        web.setBackgroundColor(getColor(R.color.bg));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 目标站点可能是 http:// 的内网地址，必须允许明文
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(true);

        web.addJavascriptInterface(new Bridge(), "DeepGravity");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleSpecial(request.getUrl().toString());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return serveLocal(request.getUrl());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // 远程页面里注入一个悬浮返回按钮（首页不需要）
                if (url != null && !url.startsWith("https://" + HOST + "/")) {
                    view.evaluateJavascript(INJECT_JS, null);
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // 只对用户在配置里显式勾了 allowSelfSigned 的主机放行
                String h = Uri.parse(error.getUrl()).getHost();
                if (h != null && trustedHosts.contains(h)) {
                    handler.proceed();
                } else {
                    handler.cancel();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // target="_blank" / window.open 一律在当前 WebView 内打开
                WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        web.loadUrl(r.getUrl().toString());
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }
        });

        setContentView(web);
        web.loadUrl(HOME);
    }

    // ── 地址存储（应用私有目录，不随备份外泄） ─────────────────────────
    private File storeFile() {
        return new File(getFilesDir(), STORE_FILE);
    }

    /** 读出用户保存的地址；没有就返回空数组字符串。 */
    private String readStore() {
        File f = storeFile();
        if (!f.exists()) return "[]";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            String json = new String(out.toByteArray(), StandardCharsets.UTF_8);
            // 兜底校验，坏数据不让界面崩
            new JSONArray(json);
            return json;
        } catch (Exception e) {
            return "[]";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private void writeStore(String json) {
        FileOutputStream out = null;
        try {
            new JSONArray(json);                       // 只接受合法 JSON 数组
            out = new FileOutputStream(storeFile(), false);
            out.write(json.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) { }
        }
    }

    /** 从已保存的地址里重建「允许自签名证书」的主机白名单。 */
    private void refreshTrustedHosts() {
        trustedHosts.clear();
        try {
            JSONArray arr = new JSONArray(readStore());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null || !o.optBoolean("allowSelfSigned", false)) continue;
                String h = Uri.parse(o.optString("url", "")).getHost();
                if (h != null) trustedHosts.add(h);
            }
        } catch (Exception ignored) { }
    }

    // ── 首页资源本地服务（虚拟 https 源） ──────────────────────────────
    private WebResourceResponse serveLocal(Uri u) {
        if (!HOST.equals(u.getHost())) return null;

        String path = u.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) path = "/index.html";

        String assetPath = "www" + path;
        String mime = mimeOf(assetPath);
        boolean isText = mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript");

        try {
            AssetManager am = getAssets();
            InputStream in = am.open(assetPath);
            HashMap<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse(mime, isText ? "utf-8" : null, 200, "OK", headers, in);
        } catch (IOException e) {
            byte[] body = ("404 " + assetPath).getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                    new HashMap<String, String>(), new ByteArrayInputStream(body));
        }
    }

    private static String mimeOf(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    // ── 首页与原生之间的调用 ───────────────────────────────────────────
    private class Bridge {

        /** 同步读取已保存的地址（返回 JSON 数组字符串）。 */
        @JavascriptInterface
        public String listSites() {
            return readStore();
        }

        /** 保存地址列表，并同步刷新自签名证书白名单。 */
        @JavascriptInterface
        public void saveSites(String json) {
            writeStore(json);
            refreshTrustedHosts();
        }

        @JavascriptInterface
        public void open(final String url) {
            if (url == null || url.isEmpty()) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    // 只有停在自己的首页时才允许打开，防止被远程页面滥用
                    if (isHome()) web.loadUrl(url);
                }
            });
        }

        @JavascriptInterface
        public void back() {
            runOnUiThread(new Runnable() {
                @Override public void run() { goBackOrHome(); }
            });
        }
    }

    private boolean isHome() {
        String cur = web != null ? web.getUrl() : null;
        return cur != null && cur.startsWith("https://" + HOST + "/");
    }

    /** 兜底：如果 JS 接口不可用，就用自定义 scheme 触发。 */
    private boolean handleSpecial(String url) {
        if (url == null || !url.startsWith("deepgravity://")) return false;
        Uri u = Uri.parse(url);
        String h = u.getHost();
        if ("open".equals(h)) {
            String target = u.getQueryParameter("u");
            if (target != null && !target.isEmpty()) web.loadUrl(target);
        } else if ("back".equals(h)) {
            goBackOrHome();
        }
        return true;
    }

    private void goBackOrHome() {
        if (web == null) return;
        if (isHome()) return;
        if (web.canGoBack()) web.goBack();
        else web.loadUrl(HOME);
    }

    // ── 返回键 ────────────────────────────────────────────────────────
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && !isHome()) {
            if (web.canGoBack()) web.goBack();
            else web.loadUrl(HOME);
            return;
        }
        super.onBackPressed();
    }

    // ── 生命周期 ──────────────────────────────────────────────────────
    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** 注入到远程页面里的悬浮返回按钮。用 Shadow DOM 隔离，不受页面样式影响。 */
    private static final String INJECT_JS =
        "(function(){"
      + "  try{"
      + "    if(location.host==='app.local') return;"
      + "    if(window.top!==window.self) return;"
      + "    function mount(){"
      + "      if(document.getElementById('__dg_root')) return;"
      + "      var host=document.createElement('div');"
      + "      host.id='__dg_root';"
      + "      host.style.cssText='position:fixed;left:14px;bottom:calc(env(safe-area-inset-bottom,0px) + 18px);"
      +                     "z-index:2147483647;pointer-events:auto;';"
      + "      var sh=host.attachShadow({mode:'open'});"
      + "      sh.innerHTML='<style>'"
      + "        +'button{all:unset;display:flex;align-items:center;justify-content:center;'"
      + "        +'width:40px;height:40px;border-radius:20px;cursor:pointer;'"
      + "        +'background:rgba(17,22,29,.66);border:1px solid rgba(255,255,255,.16);'"
      + "        +'color:#fff;font:19px/1 system-ui,sans-serif;opacity:.46;'"
      + "        +'backdrop-filter:blur(10px);-webkit-backdrop-filter:blur(10px);'"
      + "        +'box-shadow:0 6px 18px -8px rgba(0,0,0,.9);'"
      + "        +'transition:opacity .18s ease,transform .12s ease;}'"
      + "        +'button:hover,button:active{opacity:1;transform:scale(1.06);}'"
      + "        +'</style>'"
      + "        +'<button aria-label=\"返回列表\">&#8249;</button>';"
      + "      sh.querySelector('button').addEventListener('click',function(ev){"
      + "        ev.preventDefault();ev.stopPropagation();"
      + "        if(window.DeepGravity&&window.DeepGravity.back) window.DeepGravity.back();"
      + "        else location.href='deepgravity://back';"
      + "      });"
      + "      (document.body||document.documentElement).appendChild(host);"
      + "    }"
      + "    if(document.body) mount();"
      + "    else document.addEventListener('DOMContentLoaded',mount,{once:true});"
      + "  }catch(e){}"
      + "})();";
}
