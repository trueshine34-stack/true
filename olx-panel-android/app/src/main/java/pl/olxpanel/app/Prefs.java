package pl.olxpanel.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

/** Zapamietany adres panelu i token dostepu. */
final class Prefs {
  private static final String NAME = "olx_panel";
  private static final String KEY_URL = "server_url";
  private static final String KEY_TOKEN = "panel_token";

  private final SharedPreferences prefs;

  Prefs(Context context) {
    prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
  }

  String url() {
    return prefs.getString(KEY_URL, "");
  }

  String token() {
    return prefs.getString(KEY_TOKEN, "");
  }

  void save(String url, String token) {
    prefs.edit().putString(KEY_URL, url).putString(KEY_TOKEN, token).apply();
  }

  void clear() {
    prefs.edit().remove(KEY_URL).remove(KEY_TOKEN).apply();
  }

  boolean isConfigured() {
    return !url().isEmpty();
  }

  /** Pelny adres startowy: token doklejamy jako parametr, serwer odda go potem w cookie. */
  String startUrl() {
    String base = url();
    String token = token();
    if (token.isEmpty()) return base;
    return Uri.parse(base).buildUpon().appendQueryParameter("token", token).build().toString();
  }

  /** Normalizuje to, co wpisal uzytkownik: dodaje schemat i obcina koncowy ukosnik. */
  static String normalizeUrl(String input) {
    String value = input == null ? "" : input.trim();
    if (value.isEmpty()) return "";
    if (!value.matches("(?i)^https?://.*")) value = "http://" + value;
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }
}
