import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.polybot.btc5m',
  appName: 'PolyBot BTC 5m',
  webDir: 'dist',
  android: {
    // `https` keeps the WebView origin a secure context, which WebCrypto —
    // used for the L2 HMAC and for sealing the private key — requires.
    androidScheme: 'https',
    allowMixedContent: false,
  },
  // CapacitorHttp's fetch shim is deliberately left off: the L2 signature
  // covers the exact request body, and the shim reserialises it. Both the CLOB
  // and Gamma send `access-control-allow-origin: *` and allow the POLY_* auth
  // headers on preflight, so the WebView's own fetch reaches them directly.
  plugins: {},
};

export default config;
