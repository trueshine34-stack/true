package pl.olxpanel.app;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Most miedzy panelem w WebView a systemem. Daje trzy rzeczy, ktorych
 * strona sama nie moze: zapytania HTTP bez CORS, trwaly zapis danych
 * i zapis pliku kopii zapasowej.
 */
public class NativeBridge {

  interface Host {
    void runOnUi(Runnable action);
    void keepAwake(boolean enabled);
    void startOAuth(String url, String redirectUri);
    void shareFile(File file);
  }

  private static final int TIMEOUT_MS = 30000;
  private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;

  private final ExecutorService executor = Executors.newFixedThreadPool(3);
  private final WebView webView;
  private final File dataDir;
  private final Host host;

  NativeBridge(WebView webView, File dataDir, Host host) {
    this.webView = webView;
    this.dataDir = dataDir;
    this.host = host;
    if (!dataDir.exists() && !dataDir.mkdirs()) {
      android.util.Log.w("OlxPanel", "Nie udalo sie utworzyc katalogu danych");
    }
  }

  /* ------------------------------- HTTP -------------------------------- */

  /**
   * Wykonuje zapytanie w tle i oddaje wynik do JS przez window.__olxHttpResolve.
   * Zapytania ida z aplikacji, wiec nie obowiazuje ich CORS przegladarki.
   */
  @JavascriptInterface
  public void httpRequest(String id, String spec) {
    executor.execute(() -> {
      String result;
      try {
        result = perform(new JSONObject(spec)).toString();
      } catch (Exception e) {
        result = errorJson(e).toString();
      }
      deliver(id, result);
    });
  }

  private JSONObject perform(JSONObject spec) throws Exception {
    HttpURLConnection connection = null;
    try {
      URL url = new URL(spec.getString("url"));
      connection = (HttpURLConnection) url.openConnection();
      connection.setConnectTimeout(TIMEOUT_MS);
      connection.setReadTimeout(TIMEOUT_MS);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestMethod(spec.optString("method", "GET"));
      connection.setRequestProperty("User-Agent",
          "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36");

      JSONObject headers = spec.optJSONObject("headers");
      if (headers != null) {
        for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
          String key = it.next();
          connection.setRequestProperty(key, headers.getString(key));
        }
      }

      String body = spec.isNull("body") ? null : spec.optString("body", null);
      if (body != null && !body.isEmpty()) {
        connection.setDoOutput(true);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(payload);
        }
      }

      int status = connection.getResponseCode();
      // Blad tez ma tresc - panel pokazuje z niej powod odrzucenia przez OLX.
      InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
      String text = stream == null ? "" : readAll(stream);

      JSONObject result = new JSONObject();
      result.put("status", status);
      result.put("text", text);
      return result;
    } finally {
      if (connection != null) connection.disconnect();
    }
  }

  private String readAll(InputStream stream) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[16384];
    int read;
    while ((read = stream.read(chunk)) != -1) {
      buffer.write(chunk, 0, read);
      if (buffer.size() > MAX_BODY_BYTES) throw new IOException("Odpowiedz przekroczyla 8 MB");
    }
    return buffer.toString(StandardCharsets.UTF_8.name());
  }

  private JSONObject errorJson(Exception e) {
    JSONObject error = new JSONObject();
    try {
      String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      error.put("error", message);
    } catch (Exception ignored) {
      /* nic sensowniejszego juz nie zrobimy */
    }
    return error;
  }

  private void deliver(String id, String json) {
    host.runOnUi(() -> webView.evaluateJavascript(
        "window.__olxHttpResolve(" + quote(id) + "," + quote(json) + ")", null));
  }

  private static String quote(String value) {
    return JSONObject.quote(value == null ? "" : value);
  }

  /* ------------------------------ dane --------------------------------- */

  /** @return zawartosc pliku albo pusty ciag, gdy pliku jeszcze nie ma. */
  @JavascriptInterface
  public String readFile(String name) {
    File file = safeFile(name);
    if (file == null || !file.exists()) return "";
    try (InputStream in = new java.io.FileInputStream(file)) {
      return readAll(in);
    } catch (IOException e) {
      android.util.Log.e("OlxPanel", "Nie udalo sie odczytac " + name, e);
      return "";
    }
  }

  /** Zapis atomowy: najpierw plik tymczasowy, potem podmiana nazwy. */
  @JavascriptInterface
  public void writeFile(String name, String content) {
    File file = safeFile(name);
    if (file == null) return;
    File temp = new File(file.getAbsolutePath() + ".tmp");
    try (FileOutputStream out = new FileOutputStream(temp)) {
      out.write(content.getBytes(StandardCharsets.UTF_8));
      out.getFD().sync();
    } catch (IOException e) {
      android.util.Log.e("OlxPanel", "Nie udalo sie zapisac " + name, e);
      return;
    }
    if (!temp.renameTo(file)) {
      android.util.Log.e("OlxPanel", "Nie udalo sie podmienic pliku " + name);
    }
  }

  /** Nazwa pliku pochodzi ze strony - dopuszczamy tylko proste nazwy .json. */
  private File safeFile(String name) {
    if (name == null || !name.matches("[a-zA-Z0-9_-]{1,64}\\.json")) return null;
    return new File(dataDir, name);
  }

  /* ---------------------------- kopia i okna --------------------------- */

  @JavascriptInterface
  public void saveFile(String name, String content) {
    executor.execute(() -> {
      try {
        File dir = new File(dataDir.getParentFile(), "export");
        if (!dir.exists() && !dir.mkdirs()) return;
        String safeName = name != null && name.matches("[a-zA-Z0-9._-]{1,80}")
            ? name : "olx-panel-backup.json";
        File file = new File(dir, safeName);
        try (FileOutputStream out = new FileOutputStream(file)) {
          out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        host.runOnUi(() -> host.shareFile(file));
      } catch (IOException e) {
        android.util.Log.e("OlxPanel", "Nie udalo sie zapisac kopii", e);
      }
    });
  }

  @JavascriptInterface
  public void startOAuth(String url, String redirectUri) {
    host.runOnUi(() -> host.startOAuth(url, redirectUri));
  }

  @JavascriptInterface
  public void keepAwake(boolean enabled) {
    host.runOnUi(() -> host.keepAwake(enabled));
  }

  void shutdown() {
    executor.shutdownNow();
  }
}
