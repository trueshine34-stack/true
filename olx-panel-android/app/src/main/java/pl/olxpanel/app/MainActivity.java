package pl.olxpanel.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.WebViewAssetLoader;

import org.json.JSONObject;

import java.io.File;

/**
 * Panel dziala w calosci na telefonie: pliki panelu leza w assets,
 * dane w pamieci aplikacji, a siec obsluguje NativeBridge.
 * Serwer ani komputer nie sa do niczego potrzebne.
 */
public class MainActivity extends Activity implements NativeBridge.Host {

  private static final int REQUEST_FILE = 1001;

  private WebView webView;
  private SwipeRefreshLayout refresh;
  private WebViewAssetLoader assetLoader;
  private NativeBridge bridge;
  private ValueCallback<Uri[]> fileCallback;
  private Dialog oauthDialog;
  private long lastBackPress;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);

    webView = findViewById(R.id.webview);
    refresh = findViewById(R.id.refresh);

    assetLoader = PanelAssets.createLoader(this);

    bridge = new NativeBridge(webView, new File(getFilesDir(), "panel"), this);
    configureWebView();

    refresh.setColorSchemeColors(getColor(R.color.accent_light));
    refresh.setProgressBackgroundColorSchemeColor(getColor(R.color.bg_card));
    refresh.setOnRefreshListener(() -> {
      webView.reload();
      refresh.setRefreshing(false);
    });

    findViewById(R.id.button_menu).setOnClickListener(v -> showMenu());
    webView.loadUrl(PanelAssets.PANEL_URL);
  }

  // Панель — это JS-приложение, страницы берутся только из assets самой сборки.
  @android.annotation.SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
  private void configureWebView() {
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setUseWideViewPort(true);
    settings.setLoadWithOverviewMode(true);
    settings.setSupportZoom(false);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);

    CookieManager.getInstance().setAcceptCookie(true);
    webView.setBackgroundColor(getColor(R.color.bg));
    webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    webView.addJavascriptInterface(bridge, "OlxNative");

    webView.setWebViewClient(new WebViewClient() {
      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        return assetLoader.shouldInterceptRequest(request.getUrl());
      }

      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (PanelAssets.DOMAIN.equals(uri.getHost())) return false;
        openExternally(uri);
        return true;
      }
    });

    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                       FileChooserParams params) {
        if (fileCallback != null) fileCallback.onReceiveValue(null);
        fileCallback = callback;
        try {
          startActivityForResult(params.createIntent(), REQUEST_FILE);
          return true;
        } catch (Exception e) {
          fileCallback = null;
          toast("Не удалось открыть файлы");
          return false;
        }
      }
    });
  }

  private void openExternally(Uri uri) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, uri));
    } catch (Exception ignored) {
      toast("Нет приложения для этой ссылки");
    }
  }

  private void showMenu() {
    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setItems(new CharSequence[]{
            getString(R.string.menu_reload),
            getString(R.string.menu_clear),
        }, (dialog, which) -> {
          if (which == 0) {
            webView.reload();
          } else {
            webView.clearCache(true);
            webView.reload();
          }
        })
        .show();
  }

  /* --------------------------- NativeBridge.Host ------------------------ */

  @Override
  public void runOnUi(Runnable action) {
    runOnUiThread(action);
  }

  @Override
  public void keepAwake(boolean enabled) {
    // Очередь публикации идёт, пока приложение открыто — не даём экрану гаснуть.
    if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  public void shareFile(File file) {
    try {
      Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
      Intent intent = new Intent(Intent.ACTION_SEND)
          .setType("application/json")
          .putExtra(Intent.EXTRA_STREAM, uri)
          .putExtra(Intent.EXTRA_SUBJECT, file.getName())
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(Intent.createChooser(intent, getString(R.string.backup_share)));
    } catch (Exception e) {
      toast("Не удалось поделиться файлом");
    }
  }

  /**
   * Окно авторизации OLX. Ждём переход на redirect_uri и достаём из него код —
   * так пароль от OLX вводится на странице самого OLX, а не в нашем поле.
   */
  @Override
  public void startOAuth(String url, String redirectUri) {
    WebView authView = new WebView(this);
    WebSettings settings = authView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);

    authView.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return handleRedirect(request.getUrl(), redirectUri);
      }
    });

    oauthDialog = new Dialog(this, android.R.style.Theme_Material_NoActionBar);
    oauthDialog.setContentView(authView, new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    oauthDialog.setOnDismissListener(d -> {
      oauthDialog = null;
      authView.destroy();
    });
    oauthDialog.show();
    authView.loadUrl(url);
  }

  private boolean handleRedirect(Uri uri, String redirectUri) {
    if (uri == null || redirectUri == null || redirectUri.isEmpty()) return false;
    if (!uri.toString().startsWith(redirectUri)) return false;

    String code = uri.getQueryParameter("code");
    String error = uri.getQueryParameter("error_description");
    if (error == null) error = uri.getQueryParameter("error");
    if (oauthDialog != null) oauthDialog.dismiss();

    String script = "window.__olxOAuthResult("
        + JSONObject.quote(code == null ? "" : code) + ","
        + (error == null ? "null" : JSONObject.quote(error)) + ")";
    webView.evaluateJavascript(script, null);
    return true;
  }

  /* ------------------------------- система ------------------------------ */

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode != REQUEST_FILE) {
      super.onActivityResult(requestCode, resultCode, data);
      return;
    }
    if (fileCallback == null) return;
    fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
    fileCallback = null;
  }

  @Override
  public void onBackPressed() {
    if (oauthDialog != null) {
      oauthDialog.dismiss();
      return;
    }
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
      return;
    }
    // Двойное нажатие на выход — чтобы не потерять заполненную форму случайным свайпом.
    long now = System.currentTimeMillis();
    if (now - lastBackPress < 2000) {
      super.onBackPressed();
      return;
    }
    lastBackPress = now;
    toast(getString(R.string.exit_hint));
  }

  @Override
  protected void onDestroy() {
    if (bridge != null) bridge.shutdown();
    super.onDestroy();
  }

  private void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }
}
