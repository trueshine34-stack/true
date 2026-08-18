package pl.olxpanel.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Ekran startowy: adres panelu + token. Przed zapisem sprawdzamy,
 * czy pod tym adresem faktycznie odpowiada nasze API.
 */
public class SetupActivity extends Activity {

  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final Handler ui = new Handler(Looper.getMainLooper());

  private EditText inputUrl;
  private EditText inputToken;
  private TextView error;
  private Button connect;
  private ProgressBar progress;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Prefs prefs = new Prefs(this);

    // Adres juz znany - wchodzimy prosto do panelu.
    if (prefs.isConfigured() && !getIntent().getBooleanExtra("edit", false)) {
      startActivity(new Intent(this, MainActivity.class));
      finish();
      return;
    }

    setContentView(R.layout.activity_setup);
    inputUrl = findViewById(R.id.input_url);
    inputToken = findViewById(R.id.input_token);
    error = findViewById(R.id.error);
    connect = findViewById(R.id.button_connect);
    progress = findViewById(R.id.progress);

    inputUrl.setText(prefs.url());
    inputToken.setText(prefs.token());
    connect.setOnClickListener(v -> check(prefs));
  }

  private void check(Prefs prefs) {
    String url = Prefs.normalizeUrl(inputUrl.getText().toString());
    String token = inputToken.getText().toString().trim();

    if (url.isEmpty()) {
      showError(getString(R.string.error_empty_url));
      return;
    }
    if (!url.matches("(?i)^https?://.+")) {
      showError(getString(R.string.error_bad_url));
      return;
    }

    setBusy(true);
    executor.execute(() -> {
      String failure = probe(url, token);
      ui.post(() -> {
        setBusy(false);
        if (failure != null) {
          showError(failure);
          return;
        }
        prefs.save(url, token);
        startActivity(new Intent(this, MainActivity.class));
        finish();
      });
    });
  }

  /** @return komunikat bledu albo null, gdy panel odpowiada poprawnie. */
  private String probe(String url, String token) {
    HttpURLConnection connection = null;
    try {
      connection = (HttpURLConnection) new URL(url + "/api/state").openConnection();
      connection.setConnectTimeout(6000);
      connection.setReadTimeout(6000);
      connection.setRequestProperty("Accept", "application/json");
      if (!token.isEmpty()) connection.setRequestProperty("x-panel-token", token);

      int code = connection.getResponseCode();
      if (code == 401) return "Панель требует токен. Проверьте PANEL_TOKEN из .env";
      if (code >= 400) return "Панель ответила кодом " + code;
      return null;
    } catch (IOException e) {
      return getString(R.string.error_offline);
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private void showError(String message) {
    error.setText(message);
    error.setVisibility(View.VISIBLE);
  }

  private void setBusy(boolean busy) {
    connect.setEnabled(!busy);
    progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    if (busy) error.setVisibility(View.GONE);
  }

  @Override
  protected void onDestroy() {
    executor.shutdownNow();
    super.onDestroy();
  }
}
