package pl.olxpanel.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import androidx.test.core.app.ApplicationProvider;
import androidx.webkit.WebViewAssetLoader;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprawdza, ze panel da sie pobrac spod adresu, ktory laduje aplikacja -
 * razem ze wszystkim, o co ta strona sie potem prosi.
 *
 * Test nie zaklada zadnych sciezek z gory: wyciaga je z samego index.html
 * i z importow modulow. Wlasnie rozjazd miedzy zalozona a rzeczywista
 * sciezka konczy sie na telefonie bialym ekranem i ERR_INVALID_RESPONSE.
 */
@RunWith(RobolectricTestRunner.class)
public class PanelAssetsTest {

  private static final Pattern HTML_REF =
      Pattern.compile("(?:href|src)=\"([^\"]+)\"");
  private static final Pattern JS_IMPORT =
      Pattern.compile("(?:^|[\\s;])(?:import|export)[^'\"]*?from\\s*['\"]([^'\"]+)['\"]"
          + "|import\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");

  private WebViewAssetLoader loader;

  @Before
  public void setUp() {
    loader = PanelAssets.createLoader(ApplicationProvider.getApplicationContext());
  }

  private String load(String url) throws Exception {
    WebResourceResponse response = loader.shouldInterceptRequest(Uri.parse(url));
    assertNotNull("loader nie obsluzyl adresu " + url, response);
    InputStream data = response.getData();
    assertNotNull("brak pliku pod adresem " + url, data);
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    byte[] chunk = new byte[8192];
    int count;
    while ((count = data.read(chunk)) != -1) buffer.write(chunk, 0, count);
    return buffer.toString(StandardCharsets.UTF_8.name());
  }

  private String mimeOf(String url) {
    WebResourceResponse response = loader.shouldInterceptRequest(Uri.parse(url));
    return response == null ? null : response.getMimeType();
  }

  @Test
  public void stronaStartowaToPanel() throws Exception {
    String html = load(PanelAssets.PANEL_URL);
    assertTrue("to nie jest strona panelu", html.contains("OLX Panel"));
    assertTrue("brak wejscia do aplikacji", html.contains("js/app.js"));
  }

  /** Kazdy plik, o ktory prosi index.html, musi byc osiagalny pod swoim adresem. */
  @Test
  public void wszystkieOdnosnikiStronySaOsiagalne() throws Exception {
    String html = load(PanelAssets.PANEL_URL);
    List<String> checked = new ArrayList<>();

    Matcher matcher = HTML_REF.matcher(html);
    while (matcher.find()) {
      String ref = matcher.group(1);
      if (isExternal(ref)) continue;
      String url = resolve(PanelAssets.PANEL_URL, ref);
      load(url); // brak pliku = blad testu z nazwa adresu
      checked.add(ref);
    }

    assertTrue("index.html nie wskazal zadnych plikow - regula wyszukiwania sie zepsula",
        checked.size() >= 3);
    assertTrue("nie sprawdzono arkusza stylow: " + checked,
        checked.stream().anyMatch((r) -> r.endsWith(".css")));
    assertTrue("nie sprawdzono skryptu panelu: " + checked,
        checked.stream().anyMatch((r) -> r.endsWith("app.js")));
  }

  /** Przechodzi caly graf importow ES - od app.js po wspoldzielona logike. */
  @Test
  public void grafModulowLaczySieDoKonca() throws Exception {
    String entry = resolve(PanelAssets.PANEL_URL, "/js/app.js");
    Set<String> visited = new LinkedHashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(entry);

    while (!queue.isEmpty()) {
      String url = queue.poll();
      if (!visited.add(url)) continue;

      String source = load(url);
      String mime = mimeOf(url);
      // Przegladarka odrzuca modul ES podany z typem innym niz javascript.
      assertTrue("zly typ MIME dla " + url + ": " + mime,
          mime != null && mime.contains("javascript"));

      Matcher matcher = JS_IMPORT.matcher(source);
      while (matcher.find()) {
        String ref = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (ref == null || isExternal(ref)) continue;
        queue.add(resolve(url, ref));
      }
    }

    assertTrue("graf importow urwal sie za wczesnie: " + visited, visited.size() >= 6);
    assertTrue("nie dotarlismy do wspoldzielonej logiki cen: " + visited,
        visited.stream().anyMatch((u) -> u.endsWith("/shared/pricing.js")));
    assertTrue("nie dotarlismy do klienta OLX: " + visited,
        visited.stream().anyMatch((u) -> u.endsWith("/shared/olx-client.js")));
  }

  @Test
  public void serviceWorkerNieTrafiaDoAplikacji() {
    // W aplikacji pliki i tak sa lokalne; sw.js zostaje tylko w wersji przegladarkowej.
    assertFalse("sw.js nie powinien byc pakowany do APK",
        hasFile("https://" + PanelAssets.DOMAIN + "/sw.js"));
  }

  private boolean hasFile(String url) {
    WebResourceResponse response = loader.shouldInterceptRequest(Uri.parse(url));
    return response != null && response.getData() != null;
  }

  private static boolean isExternal(String ref) {
    return ref.startsWith("http://") || ref.startsWith("https://")
        || ref.startsWith("data:") || ref.startsWith("#") || ref.startsWith("mailto:");
  }

  private static String resolve(String baseUrl, String ref) {
    return URI.create(baseUrl).resolve(ref).toString();
  }
}
