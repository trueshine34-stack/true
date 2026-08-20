package com.polybot.btc5m;

import android.os.Bundle;

import com.getcapacitor.BridgeActivity;
import com.polybot.btc5m.bot.BotPlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Capacitor requires app-local plugins to be registered before the
        // bridge is built.
        registerPlugin(BotPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
