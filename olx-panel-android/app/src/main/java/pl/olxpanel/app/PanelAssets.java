package pl.olxpanel.app;

import android.content.Context;

import androidx.webkit.WebViewAssetLoader;

/**
 * Adres i sposob serwowania panelu z assets aplikacji.
 *
 * Panel siedzi w korzeniu assets, bo index.html odwoluje sie do plikow
 * absolutnie ("/css/panel.css", "/js/app.js") - tak samo jak w wersji
 * serwerowej. Przedrostek "/" sprawia, ze AssetsPathHandler szuka
 * dokladnie tam. Rozjazd tych sciezek konczy sie ERR_INVALID_RESPONSE,
 * czego kompilacja nie wychwyci - pilnuje tego PanelAssetsTest.
 */
final class PanelAssets {

  static final String DOMAIN = "appassets.androidplatform.net";
  static final String PANEL_URL = "https://" + DOMAIN + "/index.html";

  private PanelAssets() {
  }

  static WebViewAssetLoader createLoader(Context context) {
    return new WebViewAssetLoader.Builder()
        .setDomain(DOMAIN)
        .addPathHandler("/", new WebViewAssetLoader.AssetsPathHandler(context))
        .build();
  }
}
