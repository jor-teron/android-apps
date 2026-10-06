package com.jorteron.phonetv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Phone TV: full-screen WebView for the TV page with a draggable floating
 * button that opens an on-screen keypad. Keypad presses are delivered to the
 * page as real Android key events (digits, DPAD up/down, Enter).
 */
public class MainActivity extends Activity {

    private static final String TV_URL = "https://jor-teron.github.io/sites/tv/";
    private static final String PREFS = "phone_tv";
    private static final String PREF_FAB_X = "fab_x_frac";
    private static final String PREF_FAB_Y = "fab_y_frac";
    private static final long PANEL_AUTO_HIDE_MS = 5000;
    private static final float PORTRAIT_PANEL_HEIGHT_FRAC = 0.40f;
    private static final float LANDSCAPE_PANEL_WIDTH_FRAC = 1f / 3f;

    private FrameLayout root;
    private WebView webView;
    private FrameLayout fullscreenContainer;
    private View keypadPanel;
    private View fab;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hidePanelRunnable = this::hideKeypad;

    private SharedPreferences prefs;
    // Floating button position as a fraction (0..1) of the free area, so it
    // maps sensibly between portrait and landscape.
    private float fabXFrac = 1f;
    private float fabYFrac = 0.35f;
    // Landscape: which edge the keypad docks to (chosen from the button's side when opened).
    private boolean panelOnLeft = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        webView = findViewById(R.id.webview);
        fullscreenContainer = findViewById(R.id.fullscreen_container);
        keypadPanel = findViewById(R.id.keypad_panel);
        fab = findViewById(R.id.fab);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        fabXFrac = prefs.getFloat(PREF_FAB_X, 1f);
        fabYFrac = prefs.getFloat(PREF_FAB_Y, 0.35f);

        setupWebView();
        setupFab();
        setupKeypad();

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                applyFabPosition();
                if (isKeypadVisible()) layoutKeypad();
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        }
        if (webView.getUrl() == null) {
            webView.loadUrl(TV_URL);
        }
        enterImmersive();
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setBackgroundColor(0xFF000000);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAllowedScheme(request.getUrl());
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isAllowedScheme(Uri.parse(url));
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // Renderer crashed or was killed: rebuild the activity instead of crashing.
                recreate();
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                fullscreenContainer.setVisibility(View.VISIBLE);
                webView.setVisibility(View.INVISIBLE);
                hideKeypad();
                fab.bringToFront();
                enterImmersive();
            }

            @Override
            public void onHideCustomView() {
                exitCustomView();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                // Avoid the grey "play" placeholder before the page's poster loads.
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
    }

    private static boolean isAllowedScheme(Uri uri) {
        if (uri == null || uri.getScheme() == null) return false;
        String scheme = uri.getScheme().toLowerCase();
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                || scheme.equals("blob") || scheme.equals("data");
    }

    private void exitCustomView() {
        if (customView == null) return;
        fullscreenContainer.removeView(customView);
        fullscreenContainer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customView = null;
        customViewCallback = null;
        enterImmersive();
    }

    // ------------------------------------------------------- Floating button

    @SuppressLint("ClickableViewAccessibility")
    private void setupFab() {
        final int touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        fab.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        startX = v.getX();
                        startY = v.getY();
                        dragging = false;
                        v.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - downRawX;
                        float dy = e.getRawY() - downRawY;
                        if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                            v.setPressed(false);
                        }
                        if (dragging) {
                            moveFabTo(startX + dx, startY + dy);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        v.setPressed(false);
                        if (dragging) {
                            saveFabPosition();
                        } else {
                            v.performClick();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setPressed(false);
                        if (dragging) saveFabPosition();
                        return true;
                }
                return false;
            }
        });
        fab.setOnClickListener(v -> toggleKeypad());
    }

    private void moveFabTo(float x, float y) {
        float maxX = Math.max(0, root.getWidth() - fab.getWidth());
        float maxY = Math.max(0, root.getHeight() - fab.getHeight());
        fab.setX(clamp(x, 0, maxX));
        fab.setY(clamp(y, 0, maxY));
    }

    private void applyFabPosition() {
        if (root.getWidth() == 0 || fab.getWidth() == 0) {
            root.post(this::applyFabPosition);
            return;
        }
        float maxX = Math.max(0, root.getWidth() - fab.getWidth());
        float maxY = Math.max(0, root.getHeight() - fab.getHeight());
        moveFabTo(clamp(fabXFrac, 0f, 1f) * maxX, clamp(fabYFrac, 0f, 1f) * maxY);
    }

    private void saveFabPosition() {
        float maxX = Math.max(1, root.getWidth() - fab.getWidth());
        float maxY = Math.max(1, root.getHeight() - fab.getHeight());
        fabXFrac = clamp(fab.getX() / maxX, 0f, 1f);
        fabYFrac = clamp(fab.getY() / maxY, 0f, 1f);
        prefs.edit().putFloat(PREF_FAB_X, fabXFrac).putFloat(PREF_FAB_Y, fabYFrac).apply();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------ Keypad

    private void setupKeypad() {
        int[] digitIds = {R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
                R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9};
        for (int d = 0; d <= 9; d++) {
            final int keyCode = KeyEvent.KEYCODE_0 + d;
            findViewById(digitIds[d]).setOnClickListener(v -> sendKey(keyCode));
        }
        // The TV page listens for ArrowUp / ArrowDown / Enter (KeyboardEvent.key).
        findViewById(R.id.key_ch_up).setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DPAD_UP));
        findViewById(R.id.key_ch_down).setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_DPAD_DOWN));
        findViewById(R.id.key_ok).setOnClickListener(v -> sendKey(KeyEvent.KEYCODE_ENTER));
        findViewById(R.id.key_cancel).setOnClickListener(v -> hideKeypad());
        findViewById(R.id.btn_info).setOnClickListener(v -> {
            hideKeypad();
            startActivity(new Intent(this, AboutActivity.class));
        });
        // Any touch on the panel counts as interaction (resets auto-hide).
        keypadPanel.setOnTouchListener((v, e) -> {
            bumpAutoHide();
            return false;
        });
    }

    private void sendKey(int keyCode) {
        bumpAutoHide();
        if (!webView.hasFocus()) {
            webView.requestFocus(View.FOCUS_DOWN);
        }
        long now = SystemClock.uptimeMillis();
        webView.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        webView.dispatchKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, keyCode, 0));
    }

    private boolean isKeypadVisible() {
        return keypadPanel.getVisibility() == View.VISIBLE;
    }

    private void toggleKeypad() {
        if (isKeypadVisible()) hideKeypad();
        else showKeypad();
    }

    private void showKeypad() {
        // Landscape side = whichever half of the screen the floating button is on.
        float fabCenterX = fab.getX() + fab.getWidth() / 2f;
        panelOnLeft = fabCenterX < root.getWidth() / 2f;
        layoutKeypad();
        keypadPanel.setVisibility(View.VISIBLE);
        keypadPanel.bringToFront();
        // The button would cover keys in the docked panel; hide it while the keypad is open.
        fab.setVisibility(View.GONE);
        bumpAutoHide();
    }

    private void hideKeypad() {
        handler.removeCallbacks(hidePanelRunnable);
        keypadPanel.setVisibility(View.GONE);
        fab.setVisibility(View.VISIBLE);
        fab.bringToFront();
    }

    /**
     * Portrait: dial-pad docked at the bottom, full width, ~40% of the height.
     * Landscape: docked to the left/right edge, full height, ~1/3 of the width.
     */
    private void layoutKeypad() {
        int w = root.getWidth();
        int h = root.getHeight();
        if (w == 0 || h == 0) {
            root.post(this::layoutKeypad);
            return;
        }
        boolean landscape = w > h;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) keypadPanel.getLayoutParams();
        if (landscape) {
            lp.width = Math.round(w * LANDSCAPE_PANEL_WIDTH_FRAC);
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = (panelOnLeft ? Gravity.START : Gravity.END) | Gravity.TOP;
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = Math.round(h * PORTRAIT_PANEL_HEIGHT_FRAC);
            lp.gravity = Gravity.BOTTOM;
        }
        lp.setMargins(0, 0, 0, 0);
        keypadPanel.setLayoutParams(lp);

        // Keep keys out from under a notch / punch-hole (window uses shortEdges cutout mode).
        int base = dp(8);
        int padL = base, padT = base, padR = base, padB = base;
        WindowInsets insets = root.getRootWindowInsets();
        if (insets != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cut = insets.getDisplayCutout();
            if (cut != null) {
                padL += cut.getSafeInsetLeft();
                padR += cut.getSafeInsetRight();
                padB += cut.getSafeInsetBottom();
                if (landscape) padT += cut.getSafeInsetTop();
            }
        }
        keypadPanel.setPadding(padL, padT, padR, padB);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void bumpAutoHide() {
        handler.removeCallbacks(hidePanelRunnable);
        if (isKeypadVisible()) {
            handler.postDelayed(hidePanelRunnable, PANEL_AUTO_HIDE_MS);
        }
    }

    // ------------------------------------------------------------- Immersive

    @SuppressWarnings("deprecation")
    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // WebView is kept (configChanges); just re-place the button and re-hide bars.
        root.post(() -> {
            applyFabPosition();
            if (isKeypadVisible()) layoutKeypad();
        });
        enterImmersive();
    }

    // ------------------------------------------------------------- Lifecycle

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            exitCustomView();
        } else if (isKeypadVisible()) {
            hideKeypad();
        } else if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        enterImmersive();
    }

    @Override
    protected void onPause() {
        CookieManager.getInstance().flush();
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            ((ViewGroup) webView.getParent()).removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
