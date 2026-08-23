package com.trueshine.pokertracker;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

public final class Ui {

    private Ui() {}

    /** targetSdk 35 рисует под системными панелями — возвращаем контенту отступы. */
    public static void applyInsets(final View root) {
        final int left = root.getPaddingLeft();
        final int right = root.getPaddingRight();
        final int top = root.getPaddingTop();
        final int bottom = root.getPaddingBottom();

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int t, b, l, r;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets i = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    t = i.top; b = i.bottom; l = i.left; r = i.right;
                } else {
                    t = insets.getSystemWindowInsetTop();
                    b = insets.getSystemWindowInsetBottom();
                    l = insets.getSystemWindowInsetLeft();
                    r = insets.getSystemWindowInsetRight();
                }
                v.setPadding(left + l, top + t, right + r, bottom + b);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    public static int dp(View v, float value) {
        return Math.round(value * v.getResources().getDisplayMetrics().density);
    }
}
