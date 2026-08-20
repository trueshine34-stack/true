package com.polybot.btc5m;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The bot places an order once every five minutes. Android throttles
        // timers and sockets hard once the screen sleeps, so hold the screen
        // awake for as long as this activity is in front.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
