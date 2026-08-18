package pl.olxpanel.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/** Panel w WebView: pull-to-refresh, wybor pliku do importu, obsluga cofania. */
public class MainActivity extends Activity {

  private static final int REQUEST_FILE = 1001;

  private WebView webView;
  private SwipeRefreshLayout refresh;
  private View errorView;
  private Prefs prefs;
  private ValueCallback<Uri[]> fileCallback;
  private long lastBackPress;
  private boolean loadFailed;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    prefs = new Prefs(this);
    if (!prefs.isConfigured()) {
      openSetup();
      return;
    }

    setContentView(R.layout.activity_main);
    webView = findViewById(R.id.webview);
    refresh = findViewById(R.id.refresh);
    errorView = findViewById(R.id.error_view);

    configureWebView();

    refresh.setColorSchemeColors(getColor(R.color.accent_light));
    refresh.setProgressBackgroundColorSchemeColor(getColor(R.color.bg_card));
    refresh.setOnRefreshListener(this::reload);

    findViewById(R.id.button_retry).setOnClickListener(v -> reload());
    findViewById(R.id.button_change).setOnClickListener(v -> openSetup());
    findViewById(R.id.button_menu).setOnClickListener(this::showMenu);

    webView.loadUrl(prefs.startUrl());
  }

  // Панель — это JS-приложение, без скриптов показывать нечего. Загружаем только
  // собственный сервер пользователя, внешние ссылки уходят в системный браузер.
  @android.annotation.SuppressLint("SetJavaScriptEnabled")
  private void configureWebView() {
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setDatabaseEnabled(true);
    settings.setLoadWithOverviewMode(true);
    settings.setUseWideViewPort(true);
    settings.setSupportZoom(false);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setCacheMode(WebSettings.LOAD_DEFAULT);

    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

    webView.setBackgroundColor(getColor(R.color.bg));
    webView.setOverScrollMode(View.OVER_SCROLL_NEVER);

    webView.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        String host = Uri.parse(prefs.url()).getHost();
        // Свои страницы открываем внутри, ссылки на OLX и прочее — во внешнем браузере.
        if (host != null && host.equals(uri.getHost())) return false;
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
          Toast.makeText(MainActivity.this, "Нет приложения для этой ссылки", Toast.LENGTH_SHORT).show();
        }
        return true;
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        loadFailed = false;
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        refresh.setRefreshing(false);
        errorView.setVisibility(loadFailed ? View.VISIBLE : View.GONE);
        refresh.setVisibility(loadFailed ? View.GONE : View.VISIBLE);
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        // Интересует только сбой самой страницы, а не отдельной картинки.
        if (request.isForMainFrame()) loadFailed = true;
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
          Toast.makeText(MainActivity.this, "Не удалось открыть файлы", Toast.LENGTH_SHORT).show();
          return false;
        }
      }
    });
  }

  private void reload() {
    loadFailed = false;
    errorView.setVisibility(View.GONE);
    refresh.setVisibility(View.VISIBLE);
    webView.loadUrl(prefs.startUrl());
  }

  private void showMenu(View anchor) {
    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setItems(new CharSequence[]{
            getString(R.string.menu_reload),
            getString(R.string.menu_server),
            getString(R.string.menu_clear),
        }, (dialog, which) -> {
          if (which == 0) reload();
          else if (which == 1) openSetup();
          else {
            webView.clearCache(true);
            reload();
          }
        })
        .show();
  }

  private void openSetup() {
    Intent intent = new Intent(this, SetupActivity.class);
    intent.putExtra("edit", true);
    startActivity(intent);
    finish();
  }

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
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
      return;
    }
    // Двойное нажатие на выход — чтобы не терять заполненную форму случайным свайпом.
    long now = System.currentTimeMillis();
    if (now - lastBackPress < 2000) {
      super.onBackPressed();
      return;
    }
    lastBackPress = now;
    Toast.makeText(this, R.string.exit_hint, Toast.LENGTH_SHORT).show();
  }
}
