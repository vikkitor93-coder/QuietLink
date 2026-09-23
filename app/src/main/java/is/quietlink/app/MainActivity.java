package is.quietlink.app;

import android.Manifest;
import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.graphics.Typeface;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.util.Rational;
import android.view.*;
import android.widget.*;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements SessionBus.Listener {
    private static final int TAB_NEARBY = 0;
    private static final int TAB_KNOWN = 1;
    private static final int TAB_CODE = 2;
    private static final int REQ_EXPORT_LOG = 91;

    private LinearLayout root;
    private LinearLayout joinContent;
    private TextView status, verification, levelText;
    private ImageView remoteVideo;
    private ImageView localPreview;
    private TextureView remoteVideoTexture;
    private TextureView localVideoTexture;
    private Surface remoteDecodeSurface;
    private Surface localCameraSurface;
    private TextView videoStateOverlay;
    private FrameLayout fullscreenVideoRoot;
    private FrameLayout fullscreenLocalPreviewFrame;
    private FrameLayout activeInlineVideoFrame;
    private LinearLayout floatingVideoControls;
    private View fullscreenBackButton;
    private final List<View> fullscreenHiddenViews = new ArrayList<>();
    private final List<Integer> fullscreenHiddenVisibility = new ArrayList<>();
    private LinearLayout.LayoutParams savedInlineVideoLayout;
    private int savedRootPadLeft, savedRootPadTop, savedRootPadRight, savedRootPadBottom;
    private boolean videoControlsVisible = true;
    private boolean videoFullscreenActive = false;
    private long lastVideoStateCheckMs = 0;
    private boolean pipPresentation = false;
    private int lastPipWidth = 0;
    private int lastPipHeight = 0;
    private EditText codeInput;
    private RadioGroup modes;
    private Button pttButton, listenButton, remoteCameraButton, remoteVideoButton, babyMicButton, chatButton;
    private Button babyTorchButton, babyBrightnessButton, babyOwnCameraButton;
    private TextView babyStateText, babyOwnStateText;
    private long lastRemoteVideoUiFrameElapsedMs = 0L;
    private long remoteVideoUiCheckGeneration = 0L;
    private boolean micMuted = false;
    private boolean babyMicOn = true;
    private boolean babyCameraOn = true;
    private boolean babySettingsKnown = false;
    private boolean babyTorchOn = false;
    private boolean babyBrightnessBoost = false;
    private boolean babyAuxKnown = false;
    private boolean listening = false;
    private boolean remoteVideoOn = true;
    private boolean localVideoOn = true;
    private boolean sleepingBabyUi = false;
    private boolean cameraRequestInFlight = false;
    private boolean babyRoleCameraRequestInFlight = false;
    private float receiveVolume = 1.0f;
    private float soundThresholdUi = 0.12f;
    private int activeMode = SessionService.MODE_VOICE;
    private boolean activeHost = false;
    private boolean activeBabyStation = false;
    private boolean pendingBabyRoleSwap = false;
    private boolean renderedSession = false;
    private Boolean pendingHost;
    private String pendingCode;
    private int pendingMode;
    private Integer pendingHostModeSwitch;
    private int selectedJoinTab = TAB_CODE;
    private int pendingPairingTab = -1;
    private KnownDeviceStore knownDeviceStore;
    private android.app.AlertDialog incomingDialog;
    private android.app.AlertDialog outgoingDialog;
    private android.app.AlertDialog wifiDialog;
    private android.app.AlertDialog chatDialog;
    private android.app.AlertDialog diagnosticsDialog;
    private TextView diagnosticsText;
    private LinearLayout chatMessageList;
    private ScrollView chatScroll;
    private boolean wifiWarningDismissedThisForeground = false;
    private boolean restoringPersistedSession = false;
    private String lastDisconnectBanner = null;
    private long lastVideoWakeRefreshMs = 0L;
    private View babyWhiteLightOverlay;
    private boolean babyWhiteLightUiSaved = false;
    private int babyWhiteLightSavedSystemUi = 0;
    private int babyWhiteLightSavedStatusColor = Color.BLACK;
    private int babyWhiteLightSavedNavColor = Color.BLACK;
    private int babyWhiteLightSavedNavDividerColor = Color.BLACK;
    private TextView onlineDot;
    private OnlineStatus.Result onlineStatus = OnlineStatus.Result.unchecked();
    private boolean onlineStatusCheckInFlight = false;
    private boolean onlinePathTestInFlight = false;
    private long lastOnlineStatusCheckElapsedMs = 0L;

    private boolean devUnlocked = false;
    private int devHelpTapCount = 0;
    private long devLastHelpTapMs = 0;
    private boolean devDummySession = false;
    private boolean devDummyDarkRoom = false;
    private final List<SessionBus.ChatMessage> devChatMessages = new ArrayList<>();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        QuietLog.init(this);
        QuietLog.log("UI", "activity_create",
                "orientation=" + getResources().getConfiguration().orientation);
        getWindow().setStatusBarColor(bg());
        getWindow().setNavigationBarColor(bg());
        knownDeviceStore = new KnownDeviceStore(this);
        selectedJoinTab = getSharedPreferences("quietlink_ui", MODE_PRIVATE)
                .getInt("join_tab", TAB_CODE);
        devUnlocked = getSharedPreferences("quietlink_ui", MODE_PRIVATE)
                .getBoolean("dev_unlocked", false);
        micMuted = SessionBus.localMicMuted;
        // Attach before showJoinLobby()/ensurePairingMode() can start discovery,
        // otherwise a fast first discovery callback can arrive before the UI
        // has a listener.
        SessionBus.setListener(this);

        if (!SessionBus.active && SessionService.hasRecoverableCheckpoint(this)) {
            restoringPersistedSession = true;
            try {
                Intent restore = new Intent(this, SessionService.class)
                        .setAction(SessionService.ACTION_RESTORE_SESSION);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(restore);
                else startService(restore);
            } catch (Exception ignored) {}
        }

        if (SessionBus.active) {
            activeMode = SessionBus.mode;
            if (activeMode != SessionService.MODE_VIDEO
                    && activeMode != SessionService.MODE_BABY) {
                videoFullscreenActive = false;
            }
            activeHost = SessionBus.host;
            activeBabyStation = SessionBus.babyStation;
            micMuted = SessionBus.localMicMuted;
            babyMicOn = SessionBus.babyMicEnabled;
            babyCameraOn = SessionBus.babyCameraEnabled;
            babySettingsKnown = SessionBus.babySettingsKnown;
            if (activeMode == SessionService.MODE_BABY && activeBabyStation
                    && babySettingsKnown) {
                micMuted = !babyMicOn;
                localVideoOn = babyCameraOn;
            }
            babyTorchOn = SessionBus.babyTorchEnabled;
            babyBrightnessBoost = SessionBus.babyBrightnessBoost;
            babyAuxKnown = SessionBus.babyAuxKnown;
            if (!devDummySession) sleepingBabyUi = SessionBus.sleepingBaby;
            showSession(SessionBus.code, activeHost);
        } else {
            showJoinLobby();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        QuietLog.log("UI", "activity_resume",
                "mode=" + activeMode + " fullscreen=" + (videoFullscreenActive ? 1 : 0));
        SessionBus.setListener(this);
        UpdateManager.onResume(this);
        checkOnlineStatus(false);
        wifiWarningDismissedThisForeground = false;
        if (!devDummySession && SessionBus.active && activeMode != SessionService.MODE_VOICE) {
            requestVideoWakeRefresh();
        }
        if (!SessionBus.active && !devDummySession && !restoringPersistedSession) {
            maybeShowWifiWarning(false);
            if (selectedJoinTab != TAB_CODE) ensurePairingMode();
        }
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        QuietLog.log("UI", "window_focus", "focused=" + (hasFocus ? 1 : 0));
        if (hasFocus && !devDummySession && SessionBus.active
                && activeMode != SessionService.MODE_VOICE) {
            new Handler(Looper.getMainLooper()).postDelayed(this::requestVideoWakeRefresh, 220L);
        }
    }

    private void requestVideoWakeRefresh() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastVideoWakeRefreshMs < 700L) return;
        lastVideoWakeRefreshMs = now;
        try {
            startService(new Intent(this, SessionService.class)
                    .setAction(SessionService.ACTION_REFRESH_VIDEO_PIPELINE));
        } catch (Exception ignored) {}
    }

    @Override protected void onPause() {
        QuietLog.log("UI", "activity_pause",
                "pip=" + ((Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode()) ? 1 : 0));
        if (Build.VERSION.SDK_INT < 26 || !isInPictureInPictureMode()) {
            SessionBus.setListener(null);
        }
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        QuietLog.log("UI", "configuration_changed",
                "orientation=" + newConfig.orientation
                        + " remote_rot=" + SessionBus.remoteVideoRotation
                        + " local_rot=" + SessionBus.localVideoRotation);
        if (devDummySession) {
            if (Build.VERSION.SDK_INT < 26 || !isInPictureInPictureMode()) {
                showSession("", true);
            }
        } else if (SessionBus.active && activeMode != SessionService.MODE_VOICE) {
            try { startService(new Intent(this, SessionService.class).setAction(SessionService.ACTION_REFRESH_ORIENTATION)); }
            catch (Exception ignored) {}
            if (Build.VERSION.SDK_INT < 26 || !isInPictureInPictureMode()) {
                if (remoteVideoTexture != null) {
                    remoteVideoTexture.post(() ->
                            applyVideoTextureTransform(remoteVideoTexture,
                                    SessionBus.remoteVideoRotation, false, false));
                }
                if (localVideoTexture != null) {
                    localVideoTexture.post(() ->
                            applyVideoTextureTransform(localVideoTexture,
                                    SessionBus.localVideoRotation, true, false));
                }
                updateLocalPreviewLayout();
            }
        }
    }

    @Override public void onBackPressed() {
        if (videoFullscreenActive && SessionBus.active
                && (activeMode == SessionService.MODE_VIDEO
                    || activeMode == SessionService.MODE_BABY)) {
            QuietLog.log("UI", "video_fullscreen_exit", "source=back");
            exitVideoFullscreenInPlace();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (Build.VERSION.SDK_INT < 31) enterQuietLinkPictureInPicture();
    }

    @Override public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig);
        pipPresentation = inPictureInPictureMode;
        if (inPictureInPictureMode) {
            SessionBus.setListener(this);
            showPictureInPictureVideo();
        } else if (devDummySession) {
            showSession("", true);
        } else if (SessionBus.active) {
            SessionBus.setListener(this);
            showSession(SessionBus.code, SessionBus.host);
        }
    }

    @Override protected void onStop() {
        if (!isChangingConfigurations() && !SessionBus.active && !devDummySession && selectedJoinTab != TAB_CODE) {
            stopPairingModeService();
        }
        super.onStop();
    }

    private void checkOnlineStatus(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (onlineStatusCheckInFlight) return;
        if (!force && lastOnlineStatusCheckElapsedMs > 0L
                && now - lastOnlineStatusCheckElapsedMs < 30_000L) {
            refreshOnlineStatusDot();
            return;
        }

        onlineStatusCheckInFlight = true;
        OnlineStatus.check(result -> runOnUiThread(() -> {
            onlineStatusCheckInFlight = false;
            lastOnlineStatusCheckElapsedMs = SystemClock.elapsedRealtime();
            onlineStatus = result == null ? OnlineStatus.Result.unchecked() : result;
            refreshOnlineStatusDot();
        }));
    }

    private void refreshOnlineStatusDot() {
        if (onlineDot == null) return;
        OnlineStatus.Result current = onlineStatus;
        boolean checked = current != null && current.checkedAtMs > 0L;
        int dotColor = !checked
                ? muted()
                : (current.callsAvailable
                    ? Color.rgb(52,199,89)
                    : Color.rgb(255,69,58));
        onlineDot.setTextColor(dotColor);
        onlineDot.setAlpha(checked ? 1f : 0.72f);
        onlineDot.setContentDescription(!checked
                ? "Online status: checking"
                : (current.callsAvailable
                    ? "Online status: available"
                    : "Online status: local only"));
    }

    private void showOnlineStatusInfo() {
        OnlineStatus.Result current = onlineStatus;
        boolean checked = current != null && current.checkedAtMs > 0L;
        String title;
        String detail;

        if (!checked) {
            title = "Checking online status";
            detail = "QuietLink is checking whether the online service is reachable.";
            checkOnlineStatus(true);
        } else if (current.callsAvailable) {
            title = "Online available";
            detail = current.message;
        } else if (current.reachable) {
            title = "Local only";
            detail = current.message
                    + "\n\nThe online service itself is reachable, but internet peer calling is not enabled right now.";
        } else {
            title = "Local only";
            detail = current.message;
        }

        detail += "\n\nNearby / LAN / Hotspot connections remain available and do not depend on the online service.";

        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(detail)
                .setPositiveButton("RUN ONLINE PATH TEST",
                        (dialog, which) -> runOnlinePathTest())
                .setNeutralButton("REFRESH",
                        (dialog, which) -> checkOnlineStatus(true))
                .setNegativeButton("CLOSE", null)
                .show();
    }

    private void runOnlinePathTest() {
        if (onlinePathTestInFlight) return;
        onlinePathTestInFlight = true;

        new android.app.AlertDialog.Builder(this)
                .setTitle("Testing online path")
                .setMessage("QuietLink is checking whether this phone can discover a public UDP path. "
                        + "The public IP/port will not be displayed or written to the diagnostic log.")
                .setNegativeButton("CLOSE", null)
                .show();

        OnlinePathTest.run(result -> runOnUiThread(() -> {
            onlinePathTestInFlight = false;
            OnlinePathTest.Result safe = result == null
                    ? new OnlinePathTest.Result(false, false, false,
                            "Online path test: unavailable",
                            "QuietLink did not receive a test result. Local connections are unaffected.")
                    : result;

            String detail = safe.detail
                    + "\n\nCandidate discovered: " + (safe.publicCandidateFound ? "YES" : "NO")
                    + "\nSecond STUN confirmation: " + (safe.secondaryConfirmed ? "YES" : "NO")
                    + "\nMapping stable across checks: " + (safe.stableMapping ? "YES" : "NO")
                    + "\n\nNo public IP address or port is shown or logged.";

            new android.app.AlertDialog.Builder(this)
                    .setTitle(safe.title)
                    .setMessage(detail)
                    .setPositiveButton("RUN AGAIN", (dialog, which) -> runOnlinePathTest())
                    .setNegativeButton("CLOSE", null)
                    .show();
        }));
    }

    private void showHome() {
        showJoinLobby();
    }

    private void showHostLobby() {
        releaseVideoSurfaces();
        resetBrightness();
        renderedSession = false;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg());
        root = column();
        root.setPadding(dp(20),dp(24),dp(20),dp(32));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = Math.max(insets.getSystemWindowInsetTop(), insets.getStableInsetTop());
            int bottomInset = Math.max(insets.getSystemWindowInsetBottom(), insets.getStableInsetBottom());
            view.setPadding(dp(20), dp(16) + topInset, dp(20), dp(32) + bottomInset);
            return insets;
        });
        scroll.addView(root);
        setContentView(scroll);

        root.addView(text("HOST", 30, Color.WHITE, true));
        root.addView(text("Hosting always starts in Voice. Change mode after you enter the session.", 14, muted(), false), lp(-1,-2,0,4,0,22));

        String hostCode = randomCode();
        TextView code = text(formatCode(hostCode), 42, Color.WHITE, true);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0,dp(16),0,dp(16));
        code.setBackground(makeRound(panel2(),16));
        root.addView(code, lp(-1,-2,0,0,0,20));

        Button start = primary("START HOSTING");
        start.setTextSize(18);
        start.setOnClickListener(v -> startRequested(true, hostCode, SessionService.MODE_VOICE));
        root.addView(start, lp(-1,dp(64),0,0,0,16));

        Button swap = secondary("SWITCH TO JOIN");
        swap.setOnClickListener(v -> showJoinLobby());
        root.addView(swap, lp(-1,dp(54),0,0,0,0));
    }

    private void showJoinLobby() {
        releaseVideoSurfaces();
        resetBrightness();
        if (devDummySession) stopDummySessionState();
        renderedSession = false;

        root = column();
        root.setBackgroundColor(bg());
        root.setPadding(dp(12),dp(14),dp(12),dp(14));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = Math.max(insets.getSystemWindowInsetTop(), insets.getStableInsetTop());
            int bottomInset = Math.max(insets.getSystemWindowInsetBottom(), insets.getStableInsetBottom());
            view.setPadding(dp(12), dp(8) + topInset, dp(12), dp(14) + bottomInset);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        LinearLayout titleRow = row();
        TextView appTitle = text("QuietLink", 26, Color.WHITE, true);
        appTitle.setSingleLine(true);
        appTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(appTitle, new LinearLayout.LayoutParams(0,-2,1f));

        onlineDot = text("●", 16, muted(), true);
        onlineDot.setGravity(Gravity.CENTER);
        onlineDot.setContentDescription("Online status: checking");
        onlineDot.setOnClickListener(v -> showOnlineStatusInfo());
        LinearLayout.LayoutParams onlineLp = new LinearLayout.LayoutParams(dp(22),dp(42));
        onlineLp.setMargins(0,0,dp(2),0);
        titleRow.addView(onlineDot, onlineLp);
        refreshOnlineStatusDot();
        checkOnlineStatus(false);

        Button updateButton = secondary("CHECK UPDATE");
        updateButton.setTextSize(9);
        updateButton.setContentDescription("Check for QuietLink update");
        updateButton.setPadding(dp(5),0,dp(5),0);
        updateButton.setMinWidth(0);
        updateButton.setMinimumWidth(0);
        updateButton.setOnClickListener(v -> UpdateManager.checkForUpdate(this, true));
        LinearLayout.LayoutParams updateLp =
                new LinearLayout.LayoutParams(dp(84),dp(42));
        updateLp.setMargins(dp(4),0,dp(4),0);
        titleRow.addView(updateButton, updateLp);

        Button helpButton = secondary("?");
        helpButton.setTextSize(18);
        helpButton.setContentDescription("About QuietLink");
        helpButton.setPadding(0,0,0,0);
        helpButton.setMinWidth(0);
        helpButton.setMinimumWidth(0);
        helpButton.setOnClickListener(v -> showQuietLinkHelp());
        LinearLayout.LayoutParams helpLp = new LinearLayout.LayoutParams(dp(42),dp(42));
        helpLp.setMargins(dp(6),0,dp(8),0);
        titleRow.addView(helpButton, helpLp);

        if (devUnlocked) {
            Button devButton = secondary("🛠");
            devButton.setTextSize(17);
            devButton.setContentDescription("Developer tools");
            devButton.setPadding(0,0,0,0);
            devButton.setMinWidth(0);
            devButton.setMinimumWidth(0);
            devButton.setOnClickListener(v -> showDeveloperMenu());
            LinearLayout.LayoutParams devLp = new LinearLayout.LayoutParams(dp(48),dp(42));
            devLp.setMargins(0,0,dp(8),0);
            titleRow.addView(devButton, devLp);
        }

        root.addView(titleRow, lp(-1,-2,0,0,0,10));

        if (lastDisconnectBanner != null && !lastDisconnectBanner.trim().isEmpty()) {
            TextView disconnected = text(lastDisconnectBanner, 12, Color.LTGRAY, true);
            disconnected.setPadding(dp(10),dp(7),dp(10),dp(7));
            disconnected.setBackground(makeRound(panel2(),10));
            root.addView(disconnected, lp(-1,-2,0,0,0,8));
            String shown = lastDisconnectBanner;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!renderedSession && shown.equals(lastDisconnectBanner)) {
                    lastDisconnectBanner = null;
                    showJoinLobby();
                }
            }, 3500L);
        }

        LinearLayout tabs = row();
        Button nearby = selectedJoinTab == TAB_NEARBY ? primary("NEARBY") : secondary("NEARBY");
        Button known = selectedJoinTab == TAB_KNOWN ? primary("KNOWN") : secondary("KNOWN");
        Button code = selectedJoinTab == TAB_CODE ? primary("CODE") : secondary("CODE");
        nearby.setOnClickListener(v -> selectJoinTab(TAB_NEARBY));
        known.setOnClickListener(v -> selectJoinTab(TAB_KNOWN));
        code.setOnClickListener(v -> selectJoinTab(TAB_CODE));
        tabs.addView(nearby, weightLp());
        tabs.addView(known, weightLp());
        tabs.addView(code, weightLp());
        root.addView(tabs, lp(-1,dp(48),0,0,0,10));

        joinContent = column();
        LinearLayout.LayoutParams contentLp = new LinearLayout.LayoutParams(-1,0,1f);
        root.addView(joinContent, contentLp);

        renderJoinTabContent();

        if (selectedJoinTab == TAB_CODE) stopPairingModeService();
        else ensurePairingMode();
    }

    private void selectJoinTab(int tab) {
        if (tab < TAB_NEARBY || tab > TAB_CODE) return;
        selectedJoinTab = tab;
        getSharedPreferences("quietlink_ui", MODE_PRIVATE)
                .edit().putInt("join_tab", tab).apply();
        showJoinLobby();
    }

    private void renderJoinTabContent() {
        if (joinContent == null || renderedSession) return;
        joinContent.removeAllViews();
        if (selectedJoinTab == TAB_NEARBY) renderNearbyTab();
        else if (selectedJoinTab == TAB_KNOWN) renderKnownTab();
        else renderCodeTab();
    }

    private void renderCodeTab() {
        joinContent.addView(text("Enter the 6-digit host code", 14, muted(), false), lp(-1,-2,2,0,2,8));

        codeInput = new EditText(this);
        codeInput.setTextColor(Color.WHITE);
        codeInput.setHintTextColor(muted());
        codeInput.setHint("••• •••");
        codeInput.setTextSize(26);
        codeInput.setGravity(Gravity.CENTER);
        codeInput.setSingleLine();
        codeInput.setFocusable(false);
        codeInput.setFocusableInTouchMode(false);
        codeInput.setCursorVisible(false);
        codeInput.setClickable(false);
        codeInput.setLongClickable(false);
        codeInput.setShowSoftInputOnFocus(false);
        codeInput.setBackground(makeRound(panel2(),14));
        joinContent.addView(codeInput, lp(-1,dp(56),0,0,0,6));

        GridLayout keypad = new GridLayout(this);
        keypad.setColumnCount(3);
        keypad.setRowCount(4);
        keypad.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        keypad.setUseDefaultMargins(false);
        String[] keys = {"1","2","3","4","5","6","7","8","9","C","0","⌫"};
        for (int i=0;i<keys.length;i++) {
            String key = keys[i];
            Button keyButton = secondary(key);
            keyButton.setTextSize(20);
            keyButton.setMinWidth(0);
            keyButton.setMinimumWidth(0);
            keyButton.setMinHeight(0);
            keyButton.setMinimumHeight(0);
            keyButton.setPadding(0,0,0,0);
            keyButton.setOnClickListener(v -> handleJoinKey(key));
            GridLayout.LayoutParams kp = new GridLayout.LayoutParams();
            kp.width = 0;
            kp.height = 0;
            kp.columnSpec = GridLayout.spec(i % 3, 1f);
            kp.rowSpec = GridLayout.spec(i / 3, 1f);
            kp.setMargins(dp(2),dp(2),dp(2),dp(2));
            keypad.addView(keyButton,kp);
        }
        joinContent.addView(keypad, new LinearLayout.LayoutParams(-1,0,1f));

        LinearLayout actions = row();

        Button hostButton = secondary("HOST");
        hostButton.setTextSize(17);
        hostButton.setOnClickListener(v -> showHostLobby());
        actions.addView(hostButton, weightLp());

        Button join = primary("JOIN");
        join.setTextSize(17);
        join.setOnClickListener(v -> {
            String entered = codeInput.getText().toString().trim();
            if (!entered.matches("\\d{6}")) {
                Toast.makeText(this, "Enter the 6-digit code", Toast.LENGTH_SHORT).show();
            } else {
                startRequested(false, entered, SessionService.MODE_VOICE);
            }
        });
        LinearLayout.LayoutParams joinLp = weightLp();
        joinLp.setMargins(dp(6),0,0,0);
        actions.addView(join, joinLp);

        joinContent.addView(actions, lp(-1,dp(50),0,6,0,0));
    }

    private void renderNearbyTab() {
        if (SessionBus.autoConnectSuspended) {
            joinContent.addView(text("Fresh connections are manual • tap a device to connect",
                    12, Color.rgb(255,205,120), true), lp(-1,-2,2,0,2,8));
        }
        String nearbyStatus;
        if (isHotspotLocalNetwork()) nearbyStatus = SessionBus.pairingMode
                ? "Hotspot/LAN active • searching connected devices"
                : "Hotspot/LAN active • starting discovery…";
        else if (!isWifiRadioEnabled()) nearbyStatus = "Wi-Fi is off";
        else if (!isOnWifiNetwork()) nearbyStatus = "Wi-Fi is on • not connected to a network";
        else nearbyStatus = SessionBus.pairingMode ? "Pairing mode • searching this network" : "Starting pairing mode…";
        TextView pairing = text(nearbyStatus, 13, muted(), false);
        joinContent.addView(pairing, lp(-1,-2,2,0,2,10));

        List<PeerDiscovery.Peer> peers = SessionBus.nearbyPeers;
        if (peers == null || peers.isEmpty()) {
            TextView empty = text("No nearby QuietLink devices yet.", 16, Color.LTGRAY, false);
            empty.setGravity(Gravity.CENTER);
            joinContent.addView(empty, new LinearLayout.LayoutParams(-1,0,1f));
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        scroll.addView(list);
        joinContent.addView(scroll, new LinearLayout.LayoutParams(-1,0,1f));

        for (PeerDiscovery.Peer peer : peers) {
            KnownDeviceStore.KnownDevice known = knownDeviceStore.get(peer.fingerprint);
            LinearLayout card = panel();
            card.addView(text(peer.name, 17, Color.WHITE, true));
            String sub = known == null ? "Nearby device" :
                    (known.autoConnect ? "Known • Auto (no approval in pairing mode)" :
                            known.trusted ? "Known • Safe (approval required)" : "Known device");
            card.addView(text(sub, 12, muted(), false), lp(-1,-2,0,3,0,8));

            Button connect = primary("CONNECT");
            connect.setOnClickListener(v -> connectNearbyPeer(peer));
            card.addView(connect, lp(-1,dp(48),0,0,0,0));
            list.addView(card, lp(-1,-2,0,0,0,8));
        }
    }

    private void renderKnownTab() {
        if (SessionBus.autoConnectSuspended) {
            joinContent.addView(text("Fresh connections are manual • tap a device to connect",
                    12, Color.rgb(255,205,120), true), lp(-1,-2,2,0,2,8));
        }
        String knownStatus;
        if (isHotspotLocalNetwork()) knownStatus = SessionBus.pairingMode
                ? "Hotspot/LAN active • known connected devices can find you"
                : "Hotspot/LAN active • starting discovery…";
        else if (!isWifiRadioEnabled()) knownStatus = "Wi-Fi is off";
        else if (!isOnWifiNetwork()) knownStatus = "Wi-Fi is on • not connected to a network";
        else knownStatus = SessionBus.pairingMode ? "Pairing mode • known devices can find you" : "Starting pairing mode…";
        TextView pairing = text(knownStatus, 13, muted(), false);
        joinContent.addView(pairing, lp(-1,-2,2,0,2,8));

        LinearLayout trustHelp = panel();
        trustHelp.setPadding(dp(12), dp(10), dp(12), dp(10));
        trustHelp.addView(text("SAFE · ASK", 13, Color.WHITE, true));
        trustHelp.addView(text("Remembers and verifies this device. You still approve each connection.", 11, muted(), false),
                lp(-1,-2,0,2,0,6));
        trustHelp.addView(text("AUTO · NO ASK", 13, Color.WHITE, true));
        trustHelp.addView(text("Never starts a call by itself. If this verified device calls you manually, QuietLink accepts without asking. It also allows Android/service restoration.", 11, muted(), false),
                lp(-1,-2,0,2,0,0));
        joinContent.addView(trustHelp, lp(-1,-2,0,0,0,8));

        List<KnownDeviceStore.KnownDevice> devices = knownDeviceStore.list();
        if (devices.isEmpty()) {
            TextView empty = text("No known devices yet. Connect once through Nearby or Code.", 15, Color.LTGRAY, false);
            empty.setGravity(Gravity.CENTER);
            joinContent.addView(empty, new LinearLayout.LayoutParams(-1,0,1f));
            return;
        }

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column();
        scroll.addView(list);
        joinContent.addView(scroll, new LinearLayout.LayoutParams(-1,0,1f));

        for (KnownDeviceStore.KnownDevice device : devices) {
            PeerDiscovery.Peer peer = findNearbyPeer(device.fingerprint);
            LinearLayout card = panel();

            LinearLayout nameRow = row();
            TextView deviceName = text(device.name, 17, Color.WHITE, true);
            nameRow.addView(deviceName, new LinearLayout.LayoutParams(0,-2,1f));

            Button rename = secondary("✎");
            rename.setTextSize(18);
            rename.setContentDescription("Rename " + device.name);
            rename.setPadding(0,0,0,0);
            rename.setMinWidth(0);
            rename.setMinimumWidth(0);
            rename.setOnClickListener(v -> showRenameDeviceDialog(device));
            LinearLayout.LayoutParams smallActionLp = new LinearLayout.LayoutParams(dp(46),dp(40));
            smallActionLp.setMargins(dp(4),0,0,0);
            nameRow.addView(rename, smallActionLp);

            Button forget = secondary("✕");
            forget.setTextSize(18);
            forget.setContentDescription("Forget " + device.name);
            forget.setPadding(0,0,0,0);
            forget.setMinWidth(0);
            forget.setMinimumWidth(0);
            forget.setOnClickListener(v -> showForgetDeviceDialog(device));
            LinearLayout.LayoutParams forgetLp = new LinearLayout.LayoutParams(dp(46),dp(40));
            forgetLp.setMargins(dp(4),0,0,0);
            nameRow.addView(forget, forgetLp);

            card.addView(nameRow, lp(-1,-2,0,0,0,0));
            card.addView(text(peer == null ? "Offline" : "Nearby", 12, peer == null ? muted() : accent(), false), lp(-1,-2,0,3,0,8));

            String trustState;
            if (device.autoConnect) {
                trustState = "Auto • explicit trusted calls need no Accept";
            } else if (device.trusted) {
                trustState = "Safe • Accept required for each connection";
            } else {
                trustState = "Known • Accept required";
            }
            card.addView(text(trustState, 11, muted(), false), lp(-1,-2,0,0,0,8));

            Button connect = peer == null ? secondary("OFFLINE") : primary("CONNECT");
            connect.setEnabled(peer != null);
            if (peer != null) connect.setOnClickListener(v -> connectNearbyPeer(peer));
            card.addView(connect, lp(-1,dp(44),0,0,0,6));

            LinearLayout trustControls = row();

            Button safe = device.trusted ? primary("SAFE ✓ · ASK") : secondary("SAFE · ASK");
            safe.setTextSize(12);
            safe.setOnClickListener(v -> {
                knownDeviceStore.setTrusted(device.fingerprint, !device.trusted);
                renderJoinTabContent();
            });
            trustControls.addView(safe, weightLp());

            Button auto = device.autoConnect ? primary("AUTO ✓ · NO ASK") : secondary("AUTO · NO ASK");
            auto.setTextSize(12);
            auto.setOnClickListener(v -> {
                knownDeviceStore.setAutoConnect(device.fingerprint, !device.autoConnect);
                renderJoinTabContent();
            });
            trustControls.addView(auto, weightLp());

            card.addView(trustControls, lp(-1,dp(44),0,0,0,0));
            list.addView(card, lp(-1,-2,0,0,0,8));
        }
    }

    private void showForgetDeviceDialog(KnownDeviceStore.KnownDevice device) {
        if (device == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("Forget " + device.name + "?")
                .setMessage("This removes the saved device identity, Safe status, and Auto Resume. "
                        + "If you connect again later, QuietLink will treat it as a new device and "
                        + "you should verify the security key again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (dialog, which) -> {
                    knownDeviceStore.forget(device.fingerprint);
                    SessionBus.knownChanged();
                    Toast.makeText(this, device.name + " forgotten", Toast.LENGTH_SHORT).show();
                    renderJoinTabContent();
                })
                .show();
    }

    private void showQuietLinkHelp() {
        String message =
                "QuietLink connects two Android devices directly over the local network for private voice, video, and baby monitoring.\n\n"
                + "Nearby — find QuietLink devices on the same Wi-Fi.\n"
                + "Known — devices you have connected to before.\n"
                + "Code — connect using the 6-digit pairing code.\n\n"
                + "Safe · Ask — remembers and verifies the device, but you still approve each connection.\n\n"
                + "Auto · No Ask — never starts a call by itself. Explicit calls from this verified device are accepted automatically, and Android/service restoration is allowed.";

        TextView title = text("About QuietLink", 20, Color.WHITE, true);
        title.setPadding(dp(24),dp(20),dp(24),dp(8));
        title.setOnClickListener(v -> handleDeveloperUnlockTap());

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setCustomTitle(title)
                .setMessage(message)
                .setPositiveButton("Got it", null)
                .create();
        dialog.show();
    }

    private void handleDeveloperUnlockTap() {
        if (devUnlocked) {
            Toast.makeText(this, "Developer mode is already unlocked", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - devLastHelpTapMs > 1200) devHelpTapCount = 0;
        devLastHelpTapMs = now;
        devHelpTapCount++;
        if (devHelpTapCount >= 3) {
            devHelpTapCount = 0;
            devUnlocked = true;
            getSharedPreferences("quietlink_ui", MODE_PRIVATE)
                    .edit().putBoolean("dev_unlocked", true).apply();
            Toast.makeText(this, "Developer mode unlocked • 🛠", Toast.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(this::showJoinLobby, 250);
        }
    }

    private void showDeveloperMenu() {
        if (!devUnlocked) return;

        if (SessionBus.active && !devDummySession) {
            String[] options = {
                    "Live diagnostics",
                    "H.264 codec info",
                    "ROTATE ONLY • compact live panel",
                    "Video rotation lab • full",
                    "Report log to GitHub",
                    "Export privacy-safe log",
                    "Clear diagnostic log"
            };
            new android.app.AlertDialog.Builder(this)
                    .setTitle("🛠 Developer tools")
                    .setItems(options, (dialog, which) -> {
                        if (which == 0) showReliabilityDiagnostics();
                        else if (which == 1) showH264CodecInfo();
                        else if (which == 2) showQuickRotationPanel();
                        else if (which == 3) showVideoRotationLab();
                        else if (which == 4) reportDiagnosticLogToGitHub();
                        else if (which == 5) exportDiagnosticLog();
                        else {
                            QuietLog.clear(this);
                            Toast.makeText(this, "Diagnostic log cleared", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Close", null)
                    .show();
            return;
        }

        String[] options = {
                "Dummy Voice",
                "Dummy Video",
                "Dummy Baby • Parent Station",
                "Dummy Baby • Baby Station",
                "Dummy Sleeping Baby • Parent Station",
                "H.264 codec info",
                "ROTATE ONLY • compact live panel",
                "Video rotation lab • full",
                "Live diagnostics",
                "Report log to GitHub",
                "Online rendezvous test setup",
                "Export privacy-safe log",
                "Clear diagnostic log"
        };

        new android.app.AlertDialog.Builder(this)
                .setTitle("🛠 Developer tools")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) startDummySession(SessionService.MODE_VOICE, false, false);
                    else if (which == 1) startDummySession(SessionService.MODE_VIDEO, false, false);
                    else if (which == 2) startDummySession(SessionService.MODE_BABY, false, false);
                    else if (which == 3) startDummySession(SessionService.MODE_BABY, true, false);
                    else if (which == 4) startDummySession(SessionService.MODE_BABY, false, true);
                    else if (which == 5) showH264CodecInfo();
                    else if (which == 6) showQuickRotationPanel();
                    else if (which == 7) showVideoRotationLab();
                    else if (which == 8) showReliabilityDiagnostics();
                    else if (which == 9) reportDiagnosticLogToGitHub();
                    else if (which == 10) showOnlineRendezvousTestSetup();
                    else if (which == 11) exportDiagnosticLog();
                    else {
                        QuietLog.clear(this);
                        Toast.makeText(this, "Diagnostic log cleared", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private interface RotationLabChoice {
        void apply(int index);
    }

    private void showQuickRotationPanel() {
        if (!devUnlocked) return;

        final android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout box = column();
        box.setPadding(dp(10),dp(8),dp(10),dp(10));
        box.setBackground(makeRound(Color.rgb(38,40,44),18));
        scroll.addView(box);
        dialog.setContentView(scroll);

        populateQuickRotationPanel(box, dialog);

        dialog.setOnShowListener(d -> {
            Window w = dialog.getWindow();
            if (w == null) return;
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setGravity(Gravity.BOTTOM);
            boolean landscape = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    dp(landscape ? 250 : 340));
            View decor = w.getDecorView();
            decor.setPadding(dp(6),0,dp(6),dp(6));
        });
        dialog.show();
    }

    private void populateQuickRotationPanel(LinearLayout box,
                                            android.app.Dialog dialog) {
        if (box == null) return;
        box.removeAllViews();

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("ROTATE ONLY", 14, Color.WHITE, true);
        header.addView(title, new LinearLayout.LayoutParams(0,-2,1f));

        Button full = secondary("FULL LAB");
        full.setTextSize(9);
        full.setOnClickListener(v -> {
            dialog.dismiss();
            showVideoRotationLab();
        });
        header.addView(full, new LinearLayout.LayoutParams(dp(84),dp(34)));

        Button close = secondary("✕");
        close.setTextSize(13);
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(42),dp(34));
        closeLp.setMargins(dp(5),0,0,0);
        header.addView(close, closeLp);
        box.addView(header, lp(-1,dp(36),0,0,0,3));

        TextView current = text(
                "TX " + RotationLabConfig.forcedLabel(RotationLabConfig.forcedTxRotation(this))
                        + "  •  RX "
                        + RotationLabConfig.remoteModeLabel(RotationLabConfig.remoteMode(this))
                        + " +" + RotationLabConfig.remoteOffset(this) + "°"
                        + "  •  " + RotationLabConfig.previewLabel(
                            RotationLabConfig.localPreviewMode(this)),
                9, Color.LTGRAY, false);
        box.addView(current, lp(-1,-2,0,0,0,4));

        addQuickRotationChoices(box, "SENDER",
                new String[]{"AUTO","0°","90°","180°","270°"},
                forcedRotationChoiceIndex(),
                index -> RotationLabConfig.setForcedTxRotation(
                        this, index == 0 ? -1 : (index - 1) * 90),
                dialog);

        addQuickRotationChoices(box, "REMOTE OFFSET",
                new String[]{"0°","90°","180°","270°"},
                RotationLabConfig.remoteOffset(this) / 90,
                index -> RotationLabConfig.setRemoteOffset(this, index * 90),
                dialog);

        addQuickRotationChoices(box, "REMOTE DIRECTION",
                new String[]{"DIRECT","INVERSE"},
                RotationLabConfig.remoteMode(this),
                index -> RotationLabConfig.setRemoteMode(this, index),
                dialog);

        addQuickRotationChoices(box, "FORMULA",
                new String[]{"QL","ANDROID","WEBRTC","SENSOR"},
                RotationLabConfig.txFormula(this),
                index -> RotationLabConfig.setTxFormula(this, index),
                dialog);

        addQuickRotationChoices(box, "PREVIEW",
                new String[]{"STREAM","DISPLAY","NONE","INVERSE"},
                RotationLabConfig.localPreviewMode(this),
                index -> RotationLabConfig.setLocalPreviewMode(this, index),
                dialog);

        addQuickRotationChoices(box, "SOURCE",
                new String[]{"DISPLAY","PHYSICAL"},
                RotationLabConfig.rotationSource(this),
                index -> RotationLabConfig.setRotationSource(this, index),
                dialog);

        LinearLayout toggles = row();
        toggles.setGravity(Gravity.CENTER_VERTICAL);
        toggles.addView(rotationQuickToggle(
                "FRAME TX", RotationLabConfig.sendFrameRotation(this), v -> {
                    RotationLabConfig.setSendFrameRotation(
                            this, !RotationLabConfig.sendFrameRotation(this));
                    applyRotationLabNow();
                    populateQuickRotationPanel(box, dialog);
                }), new LinearLayout.LayoutParams(0,dp(34),1f));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(0,dp(34),1f);
        toggleLp.setMargins(dp(4),0,0,0);
        toggles.addView(rotationQuickToggle(
                "FRAME RX", RotationLabConfig.acceptFrameRotation(this), v -> {
                    RotationLabConfig.setAcceptFrameRotation(
                            this, !RotationLabConfig.acceptFrameRotation(this));
                    applyRotationLabNow();
                    populateQuickRotationPanel(box, dialog);
                }), toggleLp);
        LinearLayout.LayoutParams mirrorLp = new LinearLayout.LayoutParams(0,dp(34),1f);
        mirrorLp.setMargins(dp(4),0,0,0);
        toggles.addView(rotationQuickToggle(
                "MIRROR", RotationLabConfig.mirrorLocalPreview(this), v -> {
                    RotationLabConfig.setMirrorLocalPreview(
                            this, !RotationLabConfig.mirrorLocalPreview(this));
                    applyRotationLabNow();
                    populateQuickRotationPanel(box, dialog);
                }), mirrorLp);
        box.addView(toggles, lp(-1,dp(34),0,4,0,0));
    }

    private Button rotationQuickToggle(String label,
                                       boolean selected,
                                       View.OnClickListener listener) {
        Button b = selected ? primary(label + " ON") : secondary(label + " OFF");
        b.setTextSize(8);
        b.setMinHeight(0);
        b.setPadding(dp(3),0,dp(3),0);
        b.setOnClickListener(listener);
        return b;
    }

    private void addQuickRotationChoices(LinearLayout box,
                                         String label,
                                         String[] choices,
                                         int selected,
                                         RotationLabChoice choice,
                                         android.app.Dialog dialog) {
        TextView section = text(label, 8, muted(), true);
        box.addView(section, lp(-1,-2,0,3,0,1));

        LinearLayout line = row();
        line.setGravity(Gravity.CENTER);
        for (int i = 0; i < choices.length; i++) {
            final int index = i;
            Button b = i == selected ? primary(choices[i]) : secondary(choices[i]);
            b.setTextSize(8);
            b.setMinHeight(0);
            b.setPadding(dp(2),0,dp(2),0);
            b.setOnClickListener(v -> {
                try { choice.apply(index); } catch (Exception ignored) {}
                applyRotationLabNow();
                // Rebuild the contents inside the SAME dialog. The panel never
                // closes or jumps back to the developer menu during iteration.
                populateQuickRotationPanel(box, dialog);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(32),1f);
            if (i > 0) p.setMargins(dp(3),0,0,0);
            line.addView(b,p);
        }
        box.addView(line, lp(-1,dp(32),0,0,0,2));
    }

    private void showVideoRotationLab() {
        if (!devUnlocked) return;

        String[] options = {
                "Preset • " + (RotationLabConfig.enabled(this) ? "CUSTOM/TEST" : "PRODUCTION"),
                "H.264 sender formula • "
                        + RotationLabConfig.txFormulaLabel(RotationLabConfig.txFormula(this)),
                "Rotation source • "
                        + RotationLabConfig.sourceLabel(RotationLabConfig.rotationSource(this)),
                "Local preview transform • "
                        + RotationLabConfig.previewLabel(RotationLabConfig.localPreviewMode(this)),
                "Local front mirror • "
                        + (RotationLabConfig.mirrorLocalPreview(this) ? "ON" : "OFF"),
                "Per-frame H.264 rotation TX • "
                        + (RotationLabConfig.sendFrameRotation(this) ? "ON" : "OFF"),
                "Per-frame H.264 rotation RX • "
                        + (RotationLabConfig.acceptFrameRotation(this) ? "ON" : "OFF"),
                "Force sender rotation • "
                        + RotationLabConfig.forcedLabel(RotationLabConfig.forcedTxRotation(this)),
                "Remote rotation direction • "
                        + RotationLabConfig.remoteModeLabel(RotationLabConfig.remoteMode(this)),
                "Remote rotation offset • +" + RotationLabConfig.remoteOffset(this) + "°",
                "Video codec test • "
                        + (RotationLabConfig.forceJpeg(this) ? "FORCE JPEG" : "AUTO / H.264"),
                "Show test instructions",
                "Reset production defaults"
        };

        // Some older Android AlertDialog implementations do not render a
        // message and a list reliably at the same time. Keep this dialog
        // list-first so every rotation control remains visible on those phones.
        new android.app.AlertDialog.Builder(this)
                .setTitle("Video rotation lab • "
                        + (RotationLabConfig.enabled(this) ? "TEST" : "PRODUCTION"))
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showRotationPresetPicker();
                    else if (which == 1) showRotationLabChoice(
                            "H.264 sender formula",
                            new String[] {
                                    "Current QuietLink • sensor − display for both cameras",
                                    "Android relative • front sensor−display / back sensor+display",
                                    "WebRTC/JPEG style • front sensor+display / back sensor−display",
                                    "Sensor only • ignore device rotation"
                            },
                            RotationLabConfig.txFormula(this),
                            index -> RotationLabConfig.setTxFormula(this, index));
                    else if (which == 2) showRotationLabChoice(
                            "Device rotation source",
                            new String[] {
                                    "Display rotation • production behavior",
                                    "Physical orientation sensor • ignores rotation lock for testing"
                            },
                            RotationLabConfig.rotationSource(this),
                            index -> RotationLabConfig.setRotationSource(this, index));
                    else if (which == 3) showRotationLabChoice(
                            "Local TextureView transform",
                            new String[] {
                                    "Stream rotation • current QuietLink behavior",
                                    "Display-only • Android TextureView/Viewfinder-style",
                                    "No extra rotation",
                                    "Inverse stream rotation"
                            },
                            RotationLabConfig.localPreviewMode(this),
                            index -> RotationLabConfig.setLocalPreviewMode(this, index));
                    else if (which == 4) {
                        RotationLabConfig.setMirrorLocalPreview(
                                this, !RotationLabConfig.mirrorLocalPreview(this));
                        applyRotationLabNow();
                        showVideoRotationLab();
                    } else if (which == 5) {
                        RotationLabConfig.setSendFrameRotation(
                                this, !RotationLabConfig.sendFrameRotation(this));
                        applyRotationLabNow();
                        showVideoRotationLab();
                    } else if (which == 6) {
                        RotationLabConfig.setAcceptFrameRotation(
                                this, !RotationLabConfig.acceptFrameRotation(this));
                        applyRotationLabNow();
                        showVideoRotationLab();
                    } else if (which == 7) showRotationLabChoice(
                            "Force H.264 sender rotation",
                            new String[] {"Auto", "0°", "90°", "180°", "270°"},
                            forcedRotationChoiceIndex(),
                            index -> RotationLabConfig.setForcedTxRotation(
                                    this, index == 0 ? -1 : (index - 1) * 90));
                    else if (which == 8) showRotationLabChoice(
                            "Remote rotation direction",
                            new String[] {"Direct", "Inverse • 360° − reported"},
                            RotationLabConfig.remoteMode(this),
                            index -> RotationLabConfig.setRemoteMode(this, index));
                    else if (which == 9) showRotationLabChoice(
                            "Remote rotation offset",
                            new String[] {"+0°", "+90°", "+180°", "+270°"},
                            RotationLabConfig.remoteOffset(this) / 90,
                            index -> RotationLabConfig.setRemoteOffset(this, index * 90));
                    else if (which == 10) {
                        RotationLabConfig.setForceJpeg(
                                this, !RotationLabConfig.forceJpeg(this));
                        applyRotationLabNow();
                        showVideoRotationLab();
                    } else if (which == 11) {
                        showRotationLabInstructions();
                    } else {
                        RotationLabConfig.resetProduction(this);
                        applyRotationLabNow();
                        Toast.makeText(this,
                                "Rotation lab reset to production behavior",
                                Toast.LENGTH_SHORT).show();
                        showVideoRotationLab();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showRotationPresetPicker() {
        String[] presets = {
                "Production v0.3.50 baseline",
                "Android official relative + TextureView display-only",
                "WebRTC-style + per-frame rotation metadata",
                "Android relative + per-frame rotation metadata"
        };
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rotation lab preset")
                .setItems(presets, (dialog, which) -> {
                    RotationLabConfig.applyPreset(this, which);
                    applyRotationLabNow();
                    Toast.makeText(this, "Applied • " + presets[which],
                            Toast.LENGTH_SHORT).show();
                    showVideoRotationLab();
                })
                .setNegativeButton("Back", (dialog, which) -> showVideoRotationLab())
                .show();
    }

    private void showRotationLabChoice(String title,
                                       String[] options,
                                       int checked,
                                       RotationLabChoice choice) {
        int safeChecked = Math.max(0, Math.min(options.length - 1, checked));
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, safeChecked, (dialog, which) -> {
                    try { choice.apply(which); } catch (Exception ignored) {}
                    dialog.dismiss();
                    applyRotationLabNow();
                    showVideoRotationLab();
                })
                .setNegativeButton("Back", (dialog, which) -> showVideoRotationLab())
                .show();
    }

    private int forcedRotationChoiceIndex() {
        int forced = RotationLabConfig.forcedTxRotation(this);
        return forced < 0 ? 0 : (forced / 90) + 1;
    }

    private void applyRotationLabNow() {
        QuietLog.log("UI", "rotation_lab_change",
                "enabled=" + (RotationLabConfig.enabled(this) ? 1 : 0)
                        + " tx=" + RotationLabConfig.txFormula(this)
                        + " preview=" + RotationLabConfig.localPreviewMode(this)
                        + " remote_mode=" + RotationLabConfig.remoteMode(this)
                        + " remote_offset=" + RotationLabConfig.remoteOffset(this)
                        + " frame_tx=" + (RotationLabConfig.sendFrameRotation(this) ? 1 : 0)
                        + " frame_rx=" + (RotationLabConfig.acceptFrameRotation(this) ? 1 : 0)
                        + " jpeg=" + (RotationLabConfig.forceJpeg(this) ? 1 : 0));

        if (SessionBus.active && !devDummySession) {
            try {
                startService(new Intent(this, SessionService.class)
                        .setAction(SessionService.ACTION_APPLY_ROTATION_LAB));
            } catch (Exception ignored) {}
        }

        if (remoteVideoTexture != null) {
            remoteVideoTexture.post(() ->
                    applyVideoTextureTransform(remoteVideoTexture,
                            SessionBus.remoteVideoRotation, false, false));
        }
        if (localVideoTexture != null) {
            localVideoTexture.post(() ->
                    applyVideoTextureTransform(localVideoTexture,
                            SessionBus.localVideoRotation, true, true));
        }
        updateH264PictureInPictureAspect();
    }

    private void showRotationLabInstructions() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Rotation test")
                .setMessage(
                        "Fastest way to identify the correct combination:\n\n"
                        + "1. Put both phones in a Video call and keep this developer menu open on the phone you are changing.\n"
                        + "2. Start with PRODUCTION, then try ANDROID OFFICIAL and WEBRTC presets.\n"
                        + "3. Test front camera in portrait, landscape-left and landscape-right.\n"
                        + "4. Repeat with back camera.\n"
                        + "5. If only the self-preview is wrong, change Local preview transform/mirror.\n"
                        + "6. If only the other phone is wrong, change sender formula first, then remote direction/offset.\n"
                        + "7. Turn per-frame TX+RX on together to test frame-bound rotation instead of VIDEO_ROT timing.\n"
                        + "8. FORCE JPEG compares the old JPEG/EXIF path against H.264.\n\n"
                        + "Tell me which preset/settings work for each camera and orientation. "
                        + "The diagnostic log records only the chosen mode and rotation degrees—never video contents.")
                .setPositiveButton("OK", (dialog, which) -> showVideoRotationLab())
                .show();
    }

    private void showOnlineRendezvousTestSetup() {
        LinearLayout wrap = column();
        wrap.setPadding(dp(20), dp(8), dp(20), dp(4));

        TextView explanation = text(
                "Developer-only test endpoint. It is stored locally on this phone and is not written to the diagnostic log. "
                        + "While configured, Code-mode host/join will try this rendezvous in parallel with local discovery. "
                        + "The normal online status dot remains production-controlled.",
                13, muted(), false);
        wrap.addView(explanation, lp(-1,-2,0,0,0,12));

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://rendezvous.example.org");
        input.setText(OnlineTestConfig.get(this));
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(muted());
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        wrap.addView(input, lp(-1,dp(52),0,0,0,10));

        TextView result = text(
                OnlineTestConfig.get(this).isEmpty()
                        ? "Test override: OFF"
                        : "Test override: SAVED LOCALLY",
                12, Color.LTGRAY, false);
        result.setTextIsSelectable(false);
        wrap.addView(result, lp(-1,-2,0,0,0,0));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Online rendezvous test")
                .setView(wrap)
                .setPositiveButton("SAVE", null)
                .setNeutralButton("TEST HEALTH", null)
                .setNegativeButton("CLOSE", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    OnlineTestConfig.set(this, input.getText().toString());
                    String saved = OnlineTestConfig.get(this);
                    result.setText(saved.isEmpty()
                            ? "Test override: OFF"
                            : "Test override: SAVED LOCALLY");
                    Toast.makeText(this,
                            saved.isEmpty()
                                    ? "Online rendezvous test override disabled"
                                    : "Test rendezvous saved locally",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception e) {
                    result.setText("Not saved • " + e.getMessage());
                }
            });

            dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                String raw = input.getText().toString();
                try {
                    String normalized = OnlineTestConfig.normalize(raw);
                    if (normalized.isEmpty()) {
                        result.setText("Enter an HTTPS rendezvous hostname first.");
                        return;
                    }
                    result.setText("Testing HTTPS health endpoint…");
                    OnlineTestConfig.probe(normalized, probe -> runOnUiThread(() -> {
                        result.setText((probe.reachable ? "✓ " : "✕ ") + probe.message);
                    }));
                } catch (Exception e) {
                    result.setText("Invalid endpoint • " + e.getMessage());
                }
            });
        });
        dialog.show();
    }

    private void showH264CodecInfo() {
        H264Codec.Capability capability = H264Codec.probe();
        String message = capability.summary()
                + "\n\nActive path: hardware H.264 • adaptive 720p/540p/360p • 30 FPS target"
                + "\n\nJPEG remains as a compatibility fallback if H.264 fails.";

        new android.app.AlertDialog.Builder(this)
                .setTitle("H.264 video")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void reportDiagnosticLogToGitHub() {
        try {
            java.io.File dir = new java.io.File(getCacheDir(), "diagnostics");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new java.io.IOException("Could not create diagnostics cache");
            }
            java.io.File log = new java.io.File(
                    dir, "QuietLink-diagnostic-log.txt");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(log, false)) {
                out.write(QuietLog.exportText(this)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            }

            android.net.Uri logUri = UpdateApkProvider.uriForDiagnosticLog(this, log);
            String version = "current";
            try {
                String installed = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionName;
                if (installed != null && !installed.trim().isEmpty()) {
                    version = installed.trim();
                }
            } catch (Exception ignored) {}
            String title = "QuietLink " + version + " diagnostic report";
            String body = "Privacy-safe QuietLink diagnostic log attached. "
                    + "Please describe what you were doing and which phone/role had the problem. "
                    + "The log intentionally excludes IP addresses, room codes, device identity, "
                    + "chat text, and audio/video contents.";
            String issueUrl = "https://github.com/vikkitor93-coder/QuietLink/issues/new"
                    + "?title=" + java.net.URLEncoder.encode(
                            title, java.nio.charset.StandardCharsets.UTF_8.name())
                    + "&body=" + java.net.URLEncoder.encode(
                            body, java.nio.charset.StandardCharsets.UTF_8.name());

            new android.app.AlertDialog.Builder(this)
                    .setTitle("Report log to GitHub")
                    .setMessage("QuietLink prepared a privacy-safe diagnostic log.\n\n"
                            + "SHARE LOG opens Android's share sheet with the log attached. "
                            + "Choose GitHub if it is available on this phone.\n\n"
                            + "OPEN ISSUE opens a pre-filled QuietLink GitHub issue. "
                            + "If GitHub does not accept the shared attachment directly, attach "
                            + "QuietLink-diagnostic-log.txt to that issue.\n\n"
                            + "No GitHub password or token is stored by QuietLink.")
                    .setPositiveButton("SHARE LOG", (d,w) ->
                            shareDiagnosticLog(logUri, issueUrl, title))
                    .setNeutralButton("OPEN ISSUE", (d,w) ->
                            openGithubIssue(issueUrl))
                    .setNegativeButton("CLOSE", null)
                    .show();
        } catch (Exception e) {
            QuietLog.log("APP", "github_report_prepare_failed",
                    "type=" + e.getClass().getSimpleName());
            Toast.makeText(this,
                    "Could not prepare diagnostic report",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareDiagnosticLog(android.net.Uri logUri,
                                    String issueUrl,
                                    String title) {
        try {
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, title);
            send.putExtra(Intent.EXTRA_TEXT,
                    title + "\n\nGitHub issue: " + issueUrl);
            send.putExtra(Intent.EXTRA_STREAM, logUri);
            send.setClipData(ClipData.newRawUri(
                    "QuietLink diagnostic log", logUri));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(
                    send, "Send QuietLink log • choose GitHub if available"));
            QuietLog.log("APP", "github_report_share_opened", "");
        } catch (Exception e) {
            QuietLog.log("APP", "github_report_share_failed",
                    "type=" + e.getClass().getSimpleName());
            Toast.makeText(this,
                    "Could not open Android share sheet",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void openGithubIssue(String issueUrl) {
        try {
            Intent open = new Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse(issueUrl));
            startActivity(open);
            QuietLog.log("APP", "github_issue_opened", "");
        } catch (Exception e) {
            QuietLog.log("APP", "github_issue_open_failed",
                    "type=" + e.getClass().getSimpleName());
            Toast.makeText(this,
                    "Could not open GitHub issue page",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void exportDiagnosticLog() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "QuietLink-diagnostic-log.txt");
        try {
            startActivityForResult(intent, REQ_EXPORT_LOG);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open file exporter", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_EXPORT_LOG || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        try (java.io.OutputStream out =
                     getContentResolver().openOutputStream(data.getData(), "wt")) {
            if (out == null) throw new java.io.IOException("No output stream");
            out.write(QuietLog.exportText(this)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            Toast.makeText(this, "Privacy-safe log exported", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not export diagnostic log", Toast.LENGTH_LONG).show();
        }
    }

    private void showReliabilityDiagnostics() {
        if (diagnosticsDialog != null && diagnosticsDialog.isShowing()) return;

        diagnosticsText = text("", 12, Color.WHITE, false);
        diagnosticsText.setPadding(dp(16),dp(12),dp(16),dp(12));
        diagnosticsText.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(diagnosticsText, new ScrollView.LayoutParams(-1,-2));

        diagnosticsDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Live diagnostics")
                .setView(scroll)
                .setNeutralButton("Report to GitHub", (d,w) ->
                        reportDiagnosticLogToGitHub())
                .setNegativeButton("Close", null)
                .create();
        diagnosticsDialog.setOnDismissListener(d -> {
            diagnosticsDialog = null;
            diagnosticsText = null;
        });
        diagnosticsDialog.setOnShowListener(d -> refreshDiagnosticsDialog());
        diagnosticsDialog.show();
    }

    private void refreshDiagnosticsDialog() {
        if (diagnosticsDialog == null || !diagnosticsDialog.isShowing() || diagnosticsText == null) return;

        SessionBus.DiagnosticsSnapshot d = SessionBus.diagnosticsSnapshot();
        StringBuilder out = new StringBuilder();

        if (SessionBus.active) {
            out.append("LIVE SESSION\n");
            out.append("State: ").append(d.state).append("\n");
            out.append("Peer: ").append(SessionBus.peerName == null || SessionBus.peerName.isEmpty()
                    ? "Unknown" : SessionBus.peerName).append("\n");
            out.append("Network: ").append(d.network).append("\n");
            out.append("Heartbeat age: ").append(formatDiagMs(d.heartbeatAgeMs)).append("\n");
            out.append("Round-trip time: ").append(formatDiagMs(d.rttMs)).append("\n");
            out.append("Recovery events: ").append(d.recoveries).append("\n\n");

            out.append("AUDIO\n");
            out.append("Packets TX / RX: ").append(d.audioTxPackets).append(" / ").append(d.audioRxPackets).append("\n");
            out.append("TX queue: ").append(d.audioQueue).append(" frames\n");
            out.append("RX jitter: ").append(d.audioJitterFrames).append(" frames\n");
            out.append("Concealed lost frames: ").append(d.audioConcealedFrames).append("\n");
            out.append("Playback queue drops: ").append(d.audioPlaybackDrops).append("\n\n");

            out.append("VIDEO\n");
            out.append("Codec: ").append(d.codec).append("\n");
            if (d.videoBitrateBps > 0) {
                out.append("Encoder bitrate: ")
                        .append(String.format(java.util.Locale.US, "%.2f Mbps", d.videoBitrateBps / 1_000_000f))
                        .append("\n");
            }
            out.append("Encode FPS: ")
                    .append(String.format(java.util.Locale.US, "%.1f", d.videoTxFps)).append("\n");
            out.append("Rendered FPS: ")
                    .append(String.format(java.util.Locale.US, "%.1f", d.videoRxFps)).append("\n");
            out.append("Packets TX / RX: ").append(d.videoTxPackets).append(" / ").append(d.videoRxPackets).append("\n");
            out.append("TX queue: ").append(d.videoQueue).append(" packets\n");
            out.append("Dropped TX packets: ").append(d.videoDroppedTxPackets).append("\n");
            out.append("Lost/incomplete RX units: ").append(d.videoLostRxUnits).append("\n");
            out.append("Keyframe requests: ").append(d.keyFrameRequests).append("\n");
        } else {
            android.content.SharedPreferences p = getSharedPreferences("quietlink_diagnostics", MODE_PRIVATE);
            long lastEnd = p.getLong("last_session_end_ms", 0L);
            out.append("No active QuietLink session.\n\n");
            out.append("LAST SESSION\n");
            out.append("Mode: ").append(p.getString("last_session_mode", "Unknown")).append("\n");
            out.append("Reason: ").append(p.getString("last_session_reason", "No recorded session end")).append("\n");
            if (lastEnd > 0L) {
                out.append("Time: ").append(java.text.DateFormat.getDateTimeInstance()
                        .format(new java.util.Date(lastEnd))).append("\n");
            }
        }

        diagnosticsText.setText(out.toString());
        diagnosticsText.postDelayed(this::refreshDiagnosticsDialog, 750L);
    }

    private String formatDiagMs(long ms) {
        if (ms < 0L) return "—";
        if (ms < 1000L) return ms + " ms";
        return String.format(java.util.Locale.US, "%.1f s", ms / 1000f);
    }

    private String processExitReason(int reason) {
        if (Build.VERSION.SDK_INT < 30) return "Unavailable";
        switch (reason) {
            case android.app.ApplicationExitInfo.REASON_ANR: return "ANR / app stopped responding";
            case android.app.ApplicationExitInfo.REASON_CRASH: return "Java/Kotlin crash";
            case android.app.ApplicationExitInfo.REASON_CRASH_NATIVE: return "Native crash";
            case android.app.ApplicationExitInfo.REASON_LOW_MEMORY: return "Low memory";
            case android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "Excessive resource use";
            case android.app.ApplicationExitInfo.REASON_USER_REQUESTED: return "Stopped by user/system UI";
            case android.app.ApplicationExitInfo.REASON_USER_STOPPED: return "Force-stopped by user";
            case android.app.ApplicationExitInfo.REASON_SIGNALED: return "Process killed by signal";
            case android.app.ApplicationExitInfo.REASON_EXIT_SELF: return "App exited itself";
            case android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "Permission change";
            case android.app.ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "App package updated";
            default: return "Android reason code " + reason;
        }
    }

    private void startDummySession(int mode, boolean babyStation, boolean sleeping) {
        stopPairingModeService();
        devDummySession = true;
        devDummyDarkRoom = false;
        devChatMessages.clear();
        activeMode = mode;
        activeHost = true;
        activeBabyStation = babyStation;
        sleepingBabyUi = sleeping;
        listening = !sleeping;
        babyMicOn = true;
        babyCameraOn = !sleeping;
        babySettingsKnown = true;
        remoteVideoOn = babyCameraOn;
        localVideoOn = true;
        micMuted = false;
        SessionBus.peerName = "Dummy Device";
        SessionBus.latestStatus = "DEV • local dummy session";
        SessionBus.sleepingBaby = sleeping;
        showSession("", true);
    }

    private void stopDummySessionState() {
        devDummySession = false;
        devDummyDarkRoom = false;
        devChatMessages.clear();
        babyMicOn = true;
        babyCameraOn = true;
        babySettingsKnown = false;
        SessionBus.sleepingBaby = false;
        if (!SessionBus.active) {
            SessionBus.peerName = "";
            SessionBus.latestStatus = "Disconnected";
            SessionBus.latestVideo = null;
            SessionBus.latestLocalVideo = null;
        }
    }

    private android.graphics.Bitmap makeDummyVideo(boolean dark, boolean self) {
        int w = self ? 360 : 720;
        int h = self ? 480 : 960;
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(w,h,android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        canvas.drawColor(dark ? Color.rgb(4,5,7) : Color.rgb(45,62,78));
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(dark ? Color.rgb(55,60,68) : Color.WHITE);
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(self ? 28f : 48f);
        canvas.drawText(self ? "SELF PREVIEW" : (dark ? "DUMMY • DARK ROOM" : "DUMMY VIDEO"), w / 2f, h / 2f, paint);
        return bitmap;
    }

    private void showRenameDeviceDialog(KnownDeviceStore.KnownDevice device) {
        if (device == null) return;

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(device.name);
        input.setSelection(input.getText().length());
        input.setSelectAllOnFocus(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        int pad = dp(20);

        FrameLayout holder = new FrameLayout(this);
        holder.setPadding(pad,0,pad,0);
        holder.addView(input, new FrameLayout.LayoutParams(-1,dp(56)));

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Rename device")
                .setView(holder)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getText().toString().trim();
                if (name.isEmpty()) {
                    input.setError("Enter a device name");
                    return;
                }
                knownDeviceStore.rename(device.fingerprint, name);
                renderJoinTabContent();
                dialog.dismiss();
            });
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });

        dialog.show();
    }

    private PeerDiscovery.Peer findNearbyPeer(String fingerprint) {
        if (fingerprint == null) return null;
        List<PeerDiscovery.Peer> peers = SessionBus.nearbyPeers;
        if (peers == null) return null;
        for (PeerDiscovery.Peer peer : peers) {
            if (fingerprint.equals(peer.fingerprint)) return peer;
        }
        return null;
    }

    private void connectNearbyPeer(PeerDiscovery.Peer peer) {
        if (peer == null) return;
        if (!hasUsableLocalNetwork()) {
            maybeShowWifiWarning(true);
            return;
        }
        Intent i = new Intent(this, SessionService.class)
                .setAction(SessionService.ACTION_NEARBY_CONNECT)
                .putExtra(SessionService.EXTRA_PEER_HOST, peer.host)
                .putExtra(SessionService.EXTRA_PEER_PORT, peer.port)
                .putExtra(SessionService.EXTRA_PEER_FINGERPRINT, peer.fingerprint)
                .putExtra(SessionService.EXTRA_PEER_NAME, peer.name);
        startService(i);
    }

    private void ensurePairingMode() {
        if (SessionBus.active || devDummySession || selectedJoinTab == TAB_CODE) return;
        if (!hasUsableLocalNetwork()) {
            maybeShowWifiWarning(true);
            return;
        }
        List<String> missing = missingPairingPermissions();
        if (!missing.isEmpty()) {
            pendingPairingTab = selectedJoinTab;
            requestPermissions(missing.toArray(new String[0]), 45);
            return;
        }

        Intent i = new Intent(this, SessionService.class).setAction(SessionService.ACTION_PAIRING_START);
        startForegroundService(i);
    }

    private void stopPairingModeService() {
        if (!SessionBus.pairingMode) return;
        try {
            startService(new Intent(this, SessionService.class).setAction(SessionService.ACTION_PAIRING_STOP));
        } catch (Exception ignored) {}
    }

    private List<String> missingPairingPermissions() {
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            p.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        return p;
    }

    private boolean isWifiRadioEnabled() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    private boolean isHotspotLocalNetwork() {
        return !isOnWifiNetwork() && LocalBroadcastDiscovery.hasHotspotOrLocalWifiInterface();
    }

    private boolean hasUsableLocalNetwork() {
        return isOnWifiNetwork() || isHotspotLocalNetwork();
    }

    private boolean isOnWifiNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) {
            return false;
        }
    }

    private void maybeShowWifiWarning(boolean force) {
        if (SessionBus.active || devDummySession || isFinishing()) return;

        boolean wifiEnabled = isWifiRadioEnabled();
        boolean onWifi = isOnWifiNetwork();
        boolean hotspot = isHotspotLocalNetwork();
        boolean needsSharedNetwork = selectedJoinTab == TAB_NEARBY || selectedJoinTab == TAB_KNOWN;

        if (hotspot || (wifiEnabled && (onWifi || !needsSharedNetwork))) {
            if (wifiDialog != null) {
                wifiDialog.dismiss();
                wifiDialog = null;
            }
            return;
        }

        if (!force && wifiWarningDismissedThisForeground) return;
        if (wifiDialog != null && wifiDialog.isShowing()) return;

        String title;
        String message;
        if (!wifiEnabled && !hotspot) {
            title = "Wi-Fi is off";
            message = "QuietLink uses Wi-Fi or a phone hotspot for local device connections. Turn Wi-Fi on, or start a hotspot and connect the other phone to it.";
        } else {
            title = "No Wi-Fi network";
            message = "Nearby and Known devices need both phones on the same Wi-Fi network. You can connect to Wi-Fi now, or use Code mode and let QuietLink try Wi-Fi Direct.";
        }

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Open Wi-Fi settings", (dialog, which) -> {
                    wifiWarningDismissedThisForeground = true;
                    openWifiSettings();
                })
                .setNegativeButton("Not now", (dialog, which) -> {
                    wifiWarningDismissedThisForeground = true;
                    if (needsSharedNetwork) renderJoinTabContent();
                });

        if (wifiEnabled && !onWifi && needsSharedNetwork) {
            builder.setNeutralButton("Use Code", (dialog, which) -> {
                wifiWarningDismissedThisForeground = true;
                selectJoinTab(TAB_CODE);
            });
        }

        wifiDialog = builder.create();
        wifiDialog.setOnDismissListener(dialog -> wifiDialog = null);
        wifiDialog.show();
    }

    private void openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
            } else {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    private void handleJoinKey(String key) {
        if (codeInput == null) return;
        String current = codeInput.getText().toString();
        if ("C".equals(key)) {
            codeInput.setText("");
        } else if ("⌫".equals(key)) {
            if (!current.isEmpty()) codeInput.setText(current.substring(0, current.length() - 1));
        } else if (current.length() < 6) {
            codeInput.setText(current + key);
        }
    }

    private void addMode(String name, String desc, int id, boolean checked) {
        RadioButton rb = new RadioButton(this); rb.setId(1000 + id); rb.setText(name + "\n" + desc); rb.setTextColor(Color.WHITE); rb.setTextSize(15);
        rb.setPadding(dp(4),dp(8),dp(4),dp(8)); rb.setButtonTintList(android.content.res.ColorStateList.valueOf(accent())); rb.setChecked(checked); modes.addView(rb);
    }

    private void startRequested(boolean host, String code, int requestedMode) {
        if (!hasUsableLocalNetwork()) {
            maybeShowWifiWarning(true);
            return;
        }
        pendingHost = host; pendingCode = code; pendingMode = requestedMode;
        List<String> missing = missingPermissions(requestedMode, host);
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 42);
            return;
        }
        launchSession(host, code, requestedMode);
    }

    private void launchSession(boolean host, String code, int requestedMode) {
        pendingHost = null; pendingCode = null;
        activeMode = requestedMode; activeHost = host;
        sleepingBabyUi = false;
        SessionBus.sleepingBaby = false;
        remoteVideoOn = true;
        showSession(code, host);
        Intent i = new Intent(this, SessionService.class).setAction(host ? SessionService.ACTION_HOST : SessionService.ACTION_JOIN);
        i.putExtra(SessionService.EXTRA_CODE, code).putExtra(SessionService.EXTRA_MODE, requestedMode);
        startForegroundService(i);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 42 && pendingHost != null && pendingCode != null) {
            List<String> missing = missingPermissions(pendingMode, pendingHost);
            if (missing.isEmpty()) launchSession(pendingHost, pendingCode, pendingMode);
            else {
                Toast.makeText(this, "QuietLink needs the requested nearby/microphone/camera permissions for this mode", Toast.LENGTH_LONG).show();
                pendingHost = null; pendingCode = null;
            }
        } else if (requestCode == 43) {
            cameraRequestInFlight = false;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                localVideoOn = true;
                command(SessionService.ACTION_LOCAL_VIDEO, true);
                if (SessionBus.active) showSession(SessionBus.code, activeHost);
            } else {
                localVideoOn = false;
                Toast.makeText(this, "Camera denied — video can still be received", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 44) {
            Integer requested = pendingHostModeSwitch;
            pendingHostModeSwitch = null;
            if (requested != null && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                commandMode(requested);
            } else if (requested != null) {
                Toast.makeText(this, "Camera permission is needed for video or Baby mode", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 45) {
            int requestedTab = pendingPairingTab;
            pendingPairingTab = -1;
            if (requestedTab != -1 && missingPairingPermissions().isEmpty()) {
                selectedJoinTab = requestedTab;
                ensurePairingMode();
            } else if (requestedTab != -1) {
                Toast.makeText(this, "Nearby device search needs microphone and nearby-device permission", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 46) {
            boolean shouldSwap = pendingBabyRoleSwap;
            pendingBabyRoleSwap = false;
            if (shouldSwap && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startService(new Intent(this, SessionService.class).setAction(SessionService.ACTION_SWAP_BABY_ROLE));
            } else if (shouldSwap) {
                Toast.makeText(this, "Camera permission is needed to become the Baby Station", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == 47) {
            babyRoleCameraRequestInFlight = false;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                if (activeMode == SessionService.MODE_BABY && activeBabyStation) {
                    command(SessionService.ACTION_LOCAL_VIDEO, true);
                }
            } else if (activeMode == SessionService.MODE_BABY) {
                Toast.makeText(this, "Camera permission is needed if this phone becomes the Baby Station", Toast.LENGTH_LONG).show();
            }
        }
    }

    private int selectedMode() {
        if (modes == null) return SessionService.MODE_VOICE;
        int id = modes.getCheckedRadioButtonId() - 1000;
        return Math.max(0, Math.min(2, id));
    }

    private void showSession(String code, boolean host) {
        releaseVideoSurfaces();
        resetBrightness();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        renderedSession = true;
        chatButton = null;
        babyTorchButton = null;
        babyBrightnessButton = null;
        babyOwnCameraButton = null;
        babyStateText = null;
        babyOwnStateText = null;
        sleepingBabyUi = SessionBus.sleepingBaby;

        boolean parent = !activeBabyStation && activeMode == SessionService.MODE_BABY;
        boolean baby = activeBabyStation && activeMode == SessionService.MODE_BABY;
        boolean sleepingParent = parent && sleepingBabyUi;

        boolean visualSession = activeMode != SessionService.MODE_VOICE;
        configurePictureInPicture(visualSession);

        root = column();
        root.setBackgroundColor(bg());
        if (visualSession) {
            int side = sleepingParent ? 8 : 10;
            root.setPadding(dp(side),dp(8),dp(side),dp(10));
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int topInset = Math.max(insets.getSystemWindowInsetTop(), insets.getStableInsetTop());
                int bottomInset = Math.max(insets.getSystemWindowInsetBottom(), insets.getStableInsetBottom());
                view.setPadding(dp(side), dp(6) + topInset, dp(side), dp(8) + bottomInset);
                return insets;
            });
            setContentView(root);
            root.requestApplyInsets();
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setBackgroundColor(bg());
            root.setPadding(dp(16),dp(16),dp(16),dp(24));
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                int topInset = Math.max(insets.getSystemWindowInsetTop(), insets.getStableInsetTop());
                int bottomInset = Math.max(insets.getSystemWindowInsetBottom(), insets.getStableInsetBottom());
                view.setPadding(dp(16), dp(10) + topInset, dp(16), dp(24) + bottomInset);
                return insets;
            });
            scroll.addView(root);
            setContentView(scroll);
            root.requestApplyInsets();
        }

        if (!sleepingParent) {
            LinearLayout top = row();
            Button sessionBack = iconButton("←", "Back");
            sessionBack.setOnClickListener(v -> onBackPressed());
            LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(dp(44),dp(38));
            backLp.setMargins(0,0,dp(5),0);
            top.addView(sessionBack, backLp);
            top.addView(text(devDummySession ? "DEV" : (host ? "HOST" : "JOIN"), 12, accent(), true));
            String sessionLabel = devDummySession ? "Dummy Device"
                    : (code != null && code.length() == 6
                        ? formatCode(code)
                        : (SessionBus.peerName == null || SessionBus.peerName.isEmpty() ? "CONNECTED" : SessionBus.peerName));
            TextView sessionTitle = text("  " + sessionLabel, visualSession ? 18 : 22, Color.WHITE, true);
            top.addView(sessionTitle, new LinearLayout.LayoutParams(0,-2,1f));

            if (devUnlocked && !devDummySession) {
                Button liveDev = iconButton("🛠", "Live diagnostics");
                liveDev.setOnClickListener(v -> showDeveloperMenu());
                LinearLayout.LayoutParams liveDevLp = new LinearLayout.LayoutParams(dp(48),dp(38));
                liveDevLp.setMargins(0,0,dp(5),0);
                top.addView(liveDev, liveDevLp);
            }

            chatButton = iconButton("💬", "Open text chat");
            chatButton.setOnClickListener(v -> showChatDialog());
            updateChatButtonBadge();
            top.addView(chatButton, new LinearLayout.LayoutParams(dp(58),dp(38)));
            root.addView(top);

            status = text(devDummySession ? "DEV • local dummy session • no network" : SessionBus.latestStatus,
                    visualSession ? 11 : 13, muted(), false);
            root.addView(status, lp(-1,-2,0,2,0,visualSession ? 5 : 10));
            verification = null;
        } else {
            status = null;
            verification = null;
        }

        if (activeMode == SessionService.MODE_BABY) {
            String roleText = activeBabyStation
                    ? (sleepingBabyUi
                        ? "SLEEPING BABY • BABY STATION"
                        : "BABY STATION • camera + microphone")
                    : (sleepingBabyUi ? "PARENT STATION • sleeping monitor" : "PARENT STATION • monitor + controls");

            LinearLayout roleRow = row();
            TextView role = text(roleText, 12, Color.WHITE, true);
            role.setGravity(Gravity.CENTER_VERTICAL);
            role.setPadding(dp(8),dp(5),dp(8),dp(5));
            role.setBackground(makeRound(panel2(),10));
            roleRow.addView(role, new LinearLayout.LayoutParams(0,dp(38),1f));

            Button swapRole = secondary("SWAP");
            swapRole.setTextSize(11);
            swapRole.setContentDescription(activeBabyStation
                    ? "Make this device the Parent Station"
                    : "Make this device the Baby Station");
            swapRole.setOnClickListener(v -> confirmBabyRoleSwap());
            roleRow.addView(swapRole, new LinearLayout.LayoutParams(dp(74),dp(38)));

            root.addView(roleRow, lp(-1,dp(38),0,0,0,5));
            if (activeBabyStation && sleepingBabyUi) {
                String eventState = "Parent Station is monitoring";
                if (babyTorchOn) eventState += " • FLASHLIGHT ON";
                if (babyBrightnessBoost) eventState += " • BRIGHT SCREEN";
                root.addView(text(eventState, 10, Color.LTGRAY, false),
                        lp(-1,-2,2,0,2,5));
            }
        }

        root.addView(text("MODE", 10, muted(), true), lp(-1,-2,0,0,0,2));
        LinearLayout modeRow = row();
        Button voiceMode = activeMode == SessionService.MODE_VOICE ? primary("VOICE") : secondary("VOICE");
        Button videoMode = activeMode == SessionService.MODE_VIDEO ? primary("VIDEO") : secondary("VIDEO");
        voiceMode.setOnClickListener(v -> requestModeChange(SessionService.MODE_VOICE));
        videoMode.setOnClickListener(v -> requestModeChange(SessionService.MODE_VIDEO));
        modeRow.addView(voiceMode, weightLp());
        modeRow.addView(videoMode, weightLp());
        if (host || activeMode == SessionService.MODE_BABY) {
            Button babyMode = activeMode == SessionService.MODE_BABY ? primary("BABY") : secondary("BABY");
            babyMode.setEnabled(host);
            if (host) babyMode.setOnClickListener(v -> requestModeChange(SessionService.MODE_BABY));
            modeRow.addView(babyMode, weightLp());
        }
        root.addView(modeRow, lp(-1,dp(activeMode == SessionService.MODE_VOICE ? 52 : 44),0,0,0,visualSession ? 5 : 12));

        remoteVideo = null;
        localPreview = null;
        videoStateOverlay = null;
        if (activeMode != SessionService.MODE_VOICE) {
            FrameLayout videoFrame = new FrameLayout(this);
            videoFrame.setBackgroundColor(Color.BLACK);

            if (!devDummySession) attachRemoteVideoTexture(videoFrame);

            remoteVideo = new ImageView(this);
            remoteVideo.setBackgroundColor(Color.TRANSPARENT);
            remoteVideo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            videoFrame.addView(remoteVideo, new FrameLayout.LayoutParams(-1,-1));

            videoStateOverlay = text(remoteVideoOn ? "WAITING FOR VIDEO" : "VIDEO OFF", 10, Color.WHITE, true);
            videoStateOverlay.setPadding(dp(8),dp(5),dp(8),dp(5));
            videoStateOverlay.setBackground(makeRound(Color.argb(190, 28, 33, 40),10));
            FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(-2,-2, Gravity.START | Gravity.TOP);
            stateLp.setMargins(dp(8),dp(8),dp(8),dp(8));
            videoFrame.addView(videoStateOverlay, stateLp);

            if (activeMode == SessionService.MODE_VIDEO
                    || activeMode == SessionService.MODE_BABY) {
                videoFrame.setClickable(true);
                videoFrame.setContentDescription(activeMode == SessionService.MODE_BABY
                        ? "Open fullscreen baby video"
                        : "Open fullscreen video");
                videoFrame.setOnClickListener(v -> enterVideoFullscreenInPlace(videoFrame));
            }

            activeInlineVideoFrame = videoFrame;
            FrameLayout previewFrame = new FrameLayout(this);
            previewFrame.setBackgroundColor(Color.TRANSPARENT);
            if (!devDummySession) attachLocalVideoTexture(previewFrame);

            localPreview = new ImageView(this);
            localPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            localPreview.setBackgroundColor(Color.TRANSPARENT);
            localPreview.setElevation(dp(8));
            previewFrame.addView(localPreview, new FrameLayout.LayoutParams(-1,-1));

            FrameLayout.LayoutParams previewLp = makeInlinePreviewLayoutParams();
            videoFrame.addView(previewFrame, previewLp);
            fullscreenLocalPreviewFrame = previewFrame;

            android.graphics.Bitmap shownRemote = devDummySession
                    ? makeDummyVideo(devDummyDarkRoom, false)
                    : SessionBus.latestVideo;
            android.graphics.Bitmap shownLocal = devDummySession
                    ? (localVideoOn ? makeDummyVideo(false, true) : null)
                    : SessionBus.latestLocalVideo;
            if (shownRemote != null) {
                remoteVideo.setImageBitmap(shownRemote);
                if (devDummySession) updateVideoStateFromFrame(shownRemote);
            }
            if (shownLocal != null) {
                localPreview.setImageBitmap(shownLocal);
                localPreview.setVisibility(View.VISIBLE);
            } else {
                localPreview.setVisibility(View.GONE);
            }

            if (devDummySession) {
                Button darkToggle = secondary(devDummyDarkRoom ? "DEV: LIGHT ROOM" : "DEV: DARK ROOM");
                darkToggle.setTextSize(10);
                darkToggle.setOnClickListener(v -> {
                    devDummyDarkRoom = !devDummyDarkRoom;
                    lastVideoStateCheckMs = 0;
                    showSession("", true);
                });
                FrameLayout.LayoutParams darkLp = new FrameLayout.LayoutParams(dp(126),dp(38), Gravity.END | Gravity.TOP);
                darkLp.setMargins(dp(8),dp(8),dp(8),dp(8));
                videoFrame.addView(darkToggle, darkLp);
            }

            LinearLayout.LayoutParams videoLp = new LinearLayout.LayoutParams(-1, 0, 1f);
            videoLp.setMargins(0,0,0,dp(6));
            root.addView(videoFrame, videoLp);
        }

        if (sleepingParent) {
            LinearLayout sleepingHeader = row();
            sleepingHeader.setGravity(Gravity.CENTER_VERTICAL);
            Button sleepBack = iconButton("←", "Back");
            sleepBack.setOnClickListener(v -> onBackPressed());
            sleepingHeader.addView(sleepBack, new LinearLayout.LayoutParams(dp(44),dp(36)));
            sleepingHeader.addView(text("  SLEEPING BABY • PARENT STATION",
                    12, Color.WHITE, true), new LinearLayout.LayoutParams(0,dp(36),1f));
            root.addView(sleepingHeader, lp(-1,dp(36),0,0,0,5));

            LinearLayout thresholdPanel = column();
            thresholdPanel.setPadding(dp(10),dp(6),dp(10),dp(6));
            thresholdPanel.setBackground(makeRound(panelColor(),12));

            LinearLayout thresholdTop = row();
            TextView sleepingTitle = text("SLEEPING BABY", 11, accent(), true);
            TextView sensitivityLabel = text("  Alert " + Math.round(soundThresholdUi * 100f) + "%", 12, Color.WHITE, true);
            thresholdTop.addView(sleepingTitle);
            thresholdTop.addView(sensitivityLabel, new LinearLayout.LayoutParams(0,-2,1f));
            if (devUnlocked && !devDummySession) {
                Button liveDev = iconButton("🛠", "Live diagnostics");
                liveDev.setOnClickListener(v -> showDeveloperMenu());
                thresholdTop.addView(liveDev, new LinearLayout.LayoutParams(dp(44),dp(34)));
            }
            thresholdPanel.addView(thresholdTop, lp(-1,-2,0,0,0,0));

            SeekBar sensitivity = new SeekBar(this);
            sensitivity.setMax(100);
            sensitivity.setProgress(Math.max(0, Math.min(100, Math.round(soundThresholdUi * 100f))));
            sensitivity.setPadding(0,0,0,0);
            sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    soundThresholdUi = progress / 100f;
                    sensitivityLabel.setText("  Alert " + progress + "%");
                    commandLevel(SessionService.ACTION_SENSITIVITY, soundThresholdUi);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            thresholdPanel.addView(sensitivity, lp(-1,dp(30),0,0,0,0));

            levelText = text(babySettingsKnown && !babyMicOn
                    ? "Baby mic OFF • sound alerts unavailable"
                    : "Live room 0%  ▁▁▁▁▁▁▁▁", 12,
                    babySettingsKnown && !babyMicOn ? Color.rgb(255,170,170) : Color.LTGRAY, false);
            thresholdPanel.addView(levelText, lp(-1,-2,0,0,0,0));
            root.addView(thresholdPanel, lp(-1,-2,0,0,0,6));

            String stationState = babySettingsKnown
                    ? "BABY STATION • MIC " + (babyMicOn ? "ON" : "OFF")
                        + " • CAMERA " + (babyCameraOn ? "ON" : "OFF")
                    : "BABY STATION • checking remote state…";
            root.addView(text(stationState, 10,
                    babySettingsKnown ? Color.LTGRAY : muted(), true),
                    lp(-1,-2,2,0,2,4));

            LinearLayout stationControls = row();
            stationControls.setGravity(Gravity.CENTER);

            listenButton = iconButton("", "Hear room audio on this Parent Station");
            applySleepingToggleStyle(listenButton, listening,
                    "🔊  HEAR ON", "🔇  HEAR OFF");
            listenButton.setOnClickListener(v -> {
                listening = !listening;
                applySleepingToggleStyle(listenButton, listening,
                        "🔊  HEAR ON", "🔇  HEAR OFF");
                command(SessionService.ACTION_LISTEN, listening);
            });

            babyMicButton = iconButton("", "Turn Baby Station microphone on or off");
            applyRemoteBabyStateStyle(babyMicButton, babyMicOn, babySettingsKnown,
                    "🎙  BABY MIC ON", "🔇  BABY MIC OFF", "BABY MIC …");
            babyMicButton.setOnClickListener(v ->
                    command(SessionService.ACTION_REMOTE_BABY_MIC, !babyMicOn));

            remoteVideoButton = iconButton("", "Turn Baby Station camera on or off");
            applyRemoteBabyStateStyle(remoteVideoButton, babyCameraOn, babySettingsKnown,
                    "📹  BABY CAM ON", "🚫  BABY CAM OFF", "BABY CAM …");
            remoteVideoButton.setOnClickListener(v ->
                    command(SessionService.ACTION_REMOTE_BABY_CAMERA, !babyCameraOn));

            stationControls.addView(listenButton, iconWeightLp());
            stationControls.addView(babyMicButton, iconWeightLp());
            stationControls.addView(remoteVideoButton, iconWeightLp());
            root.addView(stationControls, lp(-1,dp(54),0,0,0,4));

            LinearLayout babyActions = row();
            babyActions.setGravity(Gravity.CENTER);

            remoteCameraButton = secondary("↻  SWITCH BABY CAMERA");
            remoteCameraButton.setTextSize(11);
            remoteCameraButton.setOnClickListener(v -> {
                command(SessionService.ACTION_REMOTE_SWITCH_CAMERA, true);
                if (devDummySession) Toast.makeText(this, "DEV • dummy baby camera switched", Toast.LENGTH_SHORT).show();
            });

            Button exitSleep = secondary("☀  EXIT SLEEPING");
            exitSleep.setTextSize(11);
            exitSleep.setOnClickListener(v -> {
                sleepingBabyUi = false;
                SessionBus.sleepingBaby = false;
                listening = true;
                command(SessionService.ACTION_SLEEPING_MODE, false);
                showSession(code, host);
            });

            babyActions.addView(remoteCameraButton, weightLp());
            babyActions.addView(exitSleep, weightLp());
            root.addView(babyActions, lp(-1,dp(44),0,0,0,4));

            LinearLayout roomAssist = row();
            roomAssist.setGravity(Gravity.CENTER);

            babyTorchButton = secondary(babyAuxKnown
                    ? (babyTorchOn ? "🔦 LIGHT ON" : "🔦 LIGHT OFF")
                    : "🔦 LIGHT …");
            babyTorchButton.setTextSize(10);
            babyTorchButton.setOnClickListener(v ->
                    command(SessionService.ACTION_REMOTE_BABY_TORCH, !babyTorchOn));

            babyBrightnessButton = secondary(babyAuxKnown
                    ? (babyBrightnessBoost ? "☀ BRIGHT ON" : "☀ BRIGHT OFF")
                    : "☀ BRIGHT …");
            babyBrightnessButton.setTextSize(10);
            babyBrightnessButton.setOnClickListener(v ->
                    command(SessionService.ACTION_REMOTE_BABY_BRIGHTNESS,
                            !babyBrightnessBoost));

            roomAssist.addView(babyTorchButton, weightLp());
            roomAssist.addView(babyBrightnessButton, weightLp());
            root.addView(roomAssist, lp(-1,dp(44),0,0,0,4));

            LinearLayout iconsBottom = row();
            iconsBottom.setGravity(Gravity.CENTER);

            pttButton = iconPrimary("🎙", "Hold to talk");
            applyTalkButtonState(pttButton, false, true);
            pttButton.setOnTouchListener((v,e) -> {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    command(SessionService.ACTION_PTT, true);
                    applyTalkButtonState(pttButton, true, true);
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    command(SessionService.ACTION_PTT, false);
                    applyTalkButtonState(pttButton, false, true);
                    return true;
                }
                return false;
            });

            Button battery = iconButton("🔋", "Battery reliability settings");
            battery.setOnClickListener(v -> {
                try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            });

            chatButton = iconButton("💬", "Open text chat");
            chatButton.setOnClickListener(v -> showChatDialog());
            updateChatButtonBadge();

            Button disconnectIcon = iconDanger("⏻", "Disconnect");
            disconnectIcon.setOnClickListener(v -> {
                command(SessionService.ACTION_DISCONNECT, true);
                showJoinLobby();
            });

            iconsBottom.addView(pttButton, iconWeightLp());
            iconsBottom.addView(chatButton, iconWeightLp());
            iconsBottom.addView(battery, iconWeightLp());
            iconsBottom.addView(disconnectIcon, iconWeightLp());
            root.addView(iconsBottom, lp(-1,dp(40),0,0,0,0));
        } else {
            levelText = text("Room level 0%  ▁▁▁▁▁▁▁▁", 14, Color.LTGRAY, false);
            root.addView(levelText, lp(-1,-2,0,0,0,8));

            TextView volumeLabel = text("QuietLink volume: " + Math.round(receiveVolume * 100) + "%", 13, Color.LTGRAY, false);
            root.addView(volumeLabel, lp(-1,-2,0,2,0,0));
            SeekBar volume = new SeekBar(this);
            volume.setMax(100);
            volume.setProgress(Math.round(receiveVolume * 100));
            volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    receiveVolume = progress / 100f;
                    volumeLabel.setText("QuietLink volume: " + progress + "%");
                    commandLevel(SessionService.ACTION_OUTPUT_VOLUME, receiveVolume);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            root.addView(volume, lp(-1,dp(42),0,0,0,8));

            LinearLayout row1 = row();
            boolean effectiveMicMuted = (activeMode == SessionService.MODE_BABY
                    && activeBabyStation && babySettingsKnown)
                    ? !babyMicOn : micMuted;
            Button mute = activeMode == SessionService.MODE_BABY
                    ? compactSecondary(effectiveMicMuted ? "MIC MUTED" : "MIC ON")
                    : secondary(effectiveMicMuted ? "MIC MUTED" : "MIC ON");
            mute.setOnClickListener(v -> {
                boolean currentlyMuted = (activeMode == SessionService.MODE_BABY
                        && activeBabyStation && babySettingsKnown)
                        ? !babyMicOn : micMuted;
                boolean newMuted = !currentlyMuted;
                micMuted = newMuted;
                if (activeMode == SessionService.MODE_BABY && activeBabyStation) {
                    babyMicOn = !newMuted;
                    babySettingsKnown = true;
                }
                mute.setText(newMuted ? "MIC MUTED" : "MIC ON");
                command(SessionService.ACTION_MIC_MUTE, newMuted);
            });
            if (activeMode == SessionService.MODE_BABY) {
                row1.setGravity(Gravity.CENTER);
                row1.addView(mute, compactButtonLp());
            } else {
                row1.addView(mute, weightLp());
            }

            if (activeMode == SessionService.MODE_VIDEO) {
                Button localCam = secondary("SWITCH CAMERA");
                localCam.setOnClickListener(v -> {
                    if (devDummySession) {
                        Toast.makeText(this, "DEV • dummy camera switched", Toast.LENGTH_SHORT).show();
                    } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        command(SessionService.ACTION_LOCAL_SWITCH_CAMERA, true);
                    } else {
                        requestVideoCameraPermission();
                    }
                });
                row1.addView(localCam, weightLp());
            }
            root.addView(row1, activeMode == SessionService.MODE_BABY
                    ? lp(-1,-2,0,0,0,5)
                    : lp(-1,dp(52),0,0,0,8));

            if (activeMode == SessionService.MODE_VIDEO) {
                if (!devDummySession) {
                    localVideoOn = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            && (activeHost || localVideoOn);
                }
                Button cameraToggle = secondary(localVideoOn ? "CAMERA ON" : (devDummySession ? "CAMERA OFF" : "ENABLE CAMERA"));
                cameraToggle.setOnClickListener(v -> {
                    if (!devDummySession &&
                            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        requestVideoCameraPermission();
                        return;
                    }
                    localVideoOn = !localVideoOn;
                    cameraToggle.setText(localVideoOn ? "CAMERA ON" : "CAMERA OFF");
                    command(SessionService.ACTION_LOCAL_VIDEO, localVideoOn);
                    if (devDummySession) showSession("", true);
                });
                root.addView(cameraToggle, lp(-1,dp(50),0,0,0,8));
            }

            if (parent) {
                LinearLayout sleepRow = row();
                sleepRow.setGravity(Gravity.CENTER);
                Button sleepingToggle = compactSecondary("SLEEPING BABY");
                sleepingToggle.setOnClickListener(v -> {
                    sleepingBabyUi = true;
                    SessionBus.sleepingBaby = true;
                    listening = false;
                    remoteVideoOn = false;
                    command(SessionService.ACTION_SLEEPING_MODE, true);
                    showSession(code, host);
                });
                sleepRow.addView(sleepingToggle, compactButtonLp());
                root.addView(sleepRow, lp(-1,-2,0,0,0,4));

                String babyState = babySettingsKnown
                        ? "BABY STATION • MIC " + (babyMicOn ? "ON" : "OFF")
                            + " • CAMERA " + (babyCameraOn ? "ON" : "OFF")
                        : "BABY STATION • checking remote state…";
                babyStateText = text(babyState, 10,
                        babySettingsKnown ? Color.LTGRAY : muted(), true);
                babyStateText.setGravity(Gravity.CENTER);
                root.addView(babyStateText, lp(-1,-2,2,0,2,3));

                LinearLayout parentControls = row();
                parentControls.setGravity(Gravity.CENTER);

                babyMicButton = compactSecondary("");
                applyRemoteBabyStateStyle(babyMicButton, babyMicOn, babySettingsKnown,
                        "🎙 BABY MIC ON", "🔇 BABY MIC OFF", "BABY MIC …");
                babyMicButton.setOnClickListener(v ->
                        command(SessionService.ACTION_REMOTE_BABY_MIC, !babyMicOn));

                remoteVideoButton = compactSecondary("");
                applyRemoteBabyStateStyle(remoteVideoButton, babyCameraOn, babySettingsKnown,
                        "📹 CAMERA ON", "🚫 CAMERA OFF", "CAMERA …");
                remoteVideoButton.setOnClickListener(v ->
                        command(SessionService.ACTION_REMOTE_BABY_CAMERA, !babyCameraOn));

                remoteCameraButton = compactSecondary("↻ CAMERA");
                remoteCameraButton.setOnClickListener(v -> {
                    command(SessionService.ACTION_REMOTE_SWITCH_CAMERA, true);
                    if (devDummySession) Toast.makeText(this, "DEV • dummy baby camera switched", Toast.LENGTH_SHORT).show();
                });

                parentControls.addView(babyMicButton, compactButtonLp());
                parentControls.addView(remoteVideoButton, compactButtonLp());
                parentControls.addView(remoteCameraButton, compactButtonLp());
                root.addView(parentControls, lp(-1,-2,0,0,0,4));

                LinearLayout talkRow = row();
                talkRow.setGravity(Gravity.CENTER);
                pttButton = compactPrimary("HOLD TO TALK");
                applyTalkButtonState(pttButton, false, true);
                pttButton.setOnTouchListener((v,e) -> {
                    if (e.getAction() == MotionEvent.ACTION_DOWN) {
                        command(SessionService.ACTION_PTT, true);
                        applyTalkButtonState(pttButton, true, true);
                        return true;
                    }
                    if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                        command(SessionService.ACTION_PTT, false);
                        applyTalkButtonState(pttButton, false, true);
                        return true;
                    }
                    return false;
                });
                talkRow.addView(pttButton, compactButtonLp());
                root.addView(talkRow, lp(-1,-2,0,0,0,5));
            }

            if (baby) {
                LinearLayout babyPanel = panel();
                babyPanel.addView(text("BABY STATION", 12, accent(), true));
                String ownState = "Mic " + (babyMicOn ? "ON" : "OFF")
                        + " • Camera " + (babyCameraOn ? "ON" : "OFF")
                        + (babySettingsKnown ? "" : " • syncing");
                babyOwnStateText = text(ownState, 12, Color.WHITE, true);
                babyPanel.addView(babyOwnStateText, lp(-1,-2,0,4,0,4));
                babyPanel.addView(text("The Parent Station can remotely change the baby microphone and camera.", 12, Color.LTGRAY, false), lp(-1,-2,0,2,0,4));
                babyPanel.addView(text("Parent talkback only transmits while Hold to Talk is pressed.", 12, Color.LTGRAY, false), lp(-1,-2,0,2,0,8));

                babyOwnCameraButton = compactSecondary(babyCameraOn ? "CAMERA ON" : "CAMERA OFF");
                babyOwnCameraButton.setOnClickListener(v -> {
                    if (!devDummySession
                            && checkSelfPermission(Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        requestVideoCameraPermission();
                        return;
                    }
                    boolean next = !babyCameraOn;
                    babyCameraOn = next;
                    localVideoOn = next;
                    babySettingsKnown = true;
                    babyOwnCameraButton.setText(next ? "CAMERA ON" : "CAMERA OFF");
                    command(SessionService.ACTION_LOCAL_VIDEO, next);
                });
                LinearLayout ownCameraRow = row();
                ownCameraRow.setGravity(Gravity.CENTER);
                ownCameraRow.addView(babyOwnCameraButton, compactButtonLp());
                babyPanel.addView(ownCameraRow, lp(-1,-2,0,2,0,3));

                Button black = compactSecondary("BLACK / DIM SCREEN");
                black.setOnClickListener(v -> showBlackScreen(code, host));
                LinearLayout blackRow = row();
                blackRow.setGravity(Gravity.CENTER);
                blackRow.addView(black, compactButtonLp());
                babyPanel.addView(blackRow, lp(-1,-2,0,2,0,0));
                root.addView(babyPanel, lp(-1,-2,0,2,0,12));
            }
        }

        if (!sleepingParent) {
            Button disconnect = activeMode == SessionService.MODE_BABY
                    ? compactDanger("DISCONNECT")
                    : danger("DISCONNECT");
            disconnect.setOnClickListener(v -> {
                command(SessionService.ACTION_DISCONNECT, true);
                showJoinLobby();
            });
            if (activeMode == SessionService.MODE_BABY) {
                LinearLayout disconnectRow = row();
                disconnectRow.setGravity(Gravity.CENTER);
                disconnectRow.addView(disconnect, compactButtonLp());
                root.addView(disconnectRow, lp(-1,-2,0,3,0,0));
            } else {
                root.addView(disconnect, lp(-1,dp(52),0,4,0,0));
            }
        }

        if (activeMode == SessionService.MODE_BABY && activeBabyStation
                && babyBrightnessBoost) {
            applyBabyBrightnessBoost(true);
        }
    }

    private void enterVideoFullscreenInPlace(FrameLayout videoFrame) {
        if (videoFullscreenActive || videoFrame == null || root == null) return;
        QuietLog.log("UI", "video_fullscreen_enter",
                "source=video_tap preserve_surfaces=1");
        videoFullscreenActive = true;
        activeInlineVideoFrame = videoFrame;
        fullscreenVideoRoot = videoFrame;

        fullscreenHiddenViews.clear();
        fullscreenHiddenVisibility.clear();
        savedRootPadLeft = root.getPaddingLeft();
        savedRootPadTop = root.getPaddingTop();
        savedRootPadRight = root.getPaddingRight();
        savedRootPadBottom = root.getPaddingBottom();

        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == videoFrame) continue;
            fullscreenHiddenViews.add(child);
            fullscreenHiddenVisibility.add(child.getVisibility());
            child.setVisibility(View.GONE);
        }

        ViewGroup.LayoutParams current = videoFrame.getLayoutParams();
        savedInlineVideoLayout = current instanceof LinearLayout.LayoutParams
                ? new LinearLayout.LayoutParams((LinearLayout.LayoutParams) current)
                : null;

        root.setPadding(0,0,0,0);
        LinearLayout.LayoutParams fullLp = new LinearLayout.LayoutParams(-1,0,1f);
        fullLp.setMargins(0,0,0,0);
        videoFrame.setLayoutParams(fullLp);

        installInlineFullscreenControls(videoFrame);
        videoFrame.setOnClickListener(v ->
                setFullscreenVideoControlsVisible(!videoControlsVisible));
        videoControlsVisible = true;
        setFullscreenVideoControlsVisible(true);
        updateFullscreenLocalPreviewLayout();
    }

    private void exitVideoFullscreenInPlace() {
        if (!videoFullscreenActive) return;
        FrameLayout videoFrame = activeInlineVideoFrame;
        videoFullscreenActive = false;

        if (videoFrame != null) {
            if (floatingVideoControls != null) {
                try { videoFrame.removeView(floatingVideoControls); } catch (Exception ignored) {}
            }
            if (fullscreenBackButton != null) {
                try { videoFrame.removeView(fullscreenBackButton); } catch (Exception ignored) {}
            }
            if (savedInlineVideoLayout != null) {
                videoFrame.setLayoutParams(new LinearLayout.LayoutParams(savedInlineVideoLayout));
            }
            videoFrame.setOnClickListener(v -> enterVideoFullscreenInPlace(videoFrame));
        }

        for (int i = 0; i < fullscreenHiddenViews.size(); i++) {
            View child = fullscreenHiddenViews.get(i);
            if (child != null) child.setVisibility(fullscreenHiddenVisibility.get(i));
        }
        fullscreenHiddenViews.clear();
        fullscreenHiddenVisibility.clear();

        if (root != null) {
            root.setPadding(savedRootPadLeft, savedRootPadTop,
                    savedRootPadRight, savedRootPadBottom);
        }

        floatingVideoControls = null;
        fullscreenBackButton = null;
        fullscreenVideoRoot = null;
        videoControlsVisible = true;
        setFullscreenVideoControlsVisible(true);
        updateLocalPreviewLayout();
        QuietLog.log("UI", "video_fullscreen_restored", "surfaces_preserved=1");
    }

    private void installInlineFullscreenControls(FrameLayout frame) {
        boolean babyParentFullscreen = activeMode == SessionService.MODE_BABY
                && !activeBabyStation;
        if (babyParentFullscreen) {
            installBabyParentFullscreenControls(frame);
            return;
        }

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8),dp(6),dp(8),dp(6));
        controls.setBackground(makeRound(Color.argb(225,25,27,30),32));
        controls.setElevation(dp(14));
        floatingVideoControls = controls;

        ImageButton cameraButton = floatingIconButton(
                R.drawable.ql_ic_video,
                "Turn your camera on or off",
                false);
        cameraButton.setAlpha(localVideoOn ? 1f : 0.45f);
        cameraButton.setOnClickListener(v -> {
            if (!devDummySession &&
                    checkSelfPermission(Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                requestVideoCameraPermission();
                return;
            }
            localVideoOn = !localVideoOn;
            cameraButton.setAlpha(localVideoOn ? 1f : 0.45f);
            command(SessionService.ACTION_LOCAL_VIDEO, localVideoOn);
        });

        ImageButton switchButton = floatingIconButton(
                R.drawable.ql_ic_switch_camera,
                "Switch camera",
                false);
        switchButton.setOnClickListener(v -> {
            if (devDummySession) return;
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                command(SessionService.ACTION_LOCAL_SWITCH_CAMERA, true);
            } else requestVideoCameraPermission();
        });

        ImageButton micButton = floatingIconButton(
                R.drawable.ql_ic_mic,
                "Mute or unmute microphone",
                false);
        micButton.setAlpha(!micMuted ? 1f : 0.45f);
        micButton.setOnClickListener(v -> {
            micMuted = !micMuted;
            micButton.setAlpha(micMuted ? 0.45f : 1f);
            command(SessionService.ACTION_MIC_MUTE, micMuted);
        });

        ImageButton disconnectButton = floatingIconButton(
                R.drawable.ql_ic_call_end, "Disconnect", true);
        disconnectButton.setOnClickListener(v -> {
            command(SessionService.ACTION_DISCONNECT, true);
            videoFullscreenActive = false;
            showJoinLobby();
        });

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(46),dp(46));
        iconLp.setMargins(dp(5),0,dp(5),0);
        controls.addView(cameraButton, new LinearLayout.LayoutParams(iconLp));
        controls.addView(switchButton, new LinearLayout.LayoutParams(iconLp));
        controls.addView(micButton, new LinearLayout.LayoutParams(iconLp));
        controls.addView(disconnectButton, new LinearLayout.LayoutParams(iconLp));

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                -2,dp(60),Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        controlsLp.setMargins(dp(12),dp(12),dp(12),dp(18));
        frame.addView(controls, controlsLp);

        ImageButton back = floatingIconButton(
                R.drawable.ql_ic_back, "Back to call controls", false);
        back.setOnClickListener(v -> exitVideoFullscreenInPlace());
        fullscreenBackButton = back;
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(
                dp(46),dp(46),Gravity.START | Gravity.TOP);
        backLp.setMargins(dp(12),dp(12),dp(12),dp(12));
        frame.addView(back, backLp);
    }

    private void installBabyParentFullscreenControls(FrameLayout frame) {
        LinearLayout panel = column();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(8),dp(7),dp(8),dp(7));
        panel.setBackground(makeRound(Color.argb(232,25,27,30),18));
        panel.setElevation(dp(14));
        floatingVideoControls = panel;

        TextView state = text(
                babySettingsKnown
                        ? "BABY STATION • MIC " + (babyMicOn ? "ON" : "OFF")
                            + " • CAMERA " + (babyCameraOn ? "ON" : "OFF")
                        : "BABY STATION • checking remote state…",
                10,
                babySettingsKnown ? Color.LTGRAY : muted(),
                true);
        state.setGravity(Gravity.CENTER);
        babyStateText = state;
        panel.addView(state, lp(-1,-2,0,0,0,4));

        LinearLayout sensitivityRow = row();
        sensitivityRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sensitivityLabel = text(
                "Alert " + Math.round(soundThresholdUi * 100f) + "%",
                10, Color.WHITE, true);
        sensitivityRow.addView(sensitivityLabel, new LinearLayout.LayoutParams(-2,-2));

        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(100);
        sensitivity.setProgress(Math.max(0, Math.min(100,
                Math.round(soundThresholdUi * 100f))));
        sensitivity.setPadding(dp(6),0,0,0);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                soundThresholdUi = progress / 100f;
                sensitivityLabel.setText("Alert " + progress + "%");
                commandLevel(SessionService.ACTION_SENSITIVITY, soundThresholdUi);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        sensitivityRow.addView(sensitivity,
                new LinearLayout.LayoutParams(0,dp(28),1f));
        panel.addView(sensitivityRow, lp(-1,dp(30),0,0,0,3));

        LinearLayout row1 = row();
        row1.setGravity(Gravity.CENTER);

        listenButton = compactSecondary("");
        applySleepingToggleStyle(listenButton, listening,
                "🔊 HEAR ON", "🔇 HEAR OFF");
        listenButton.setOnClickListener(v -> {
            listening = !listening;
            applySleepingToggleStyle(listenButton, listening,
                    "🔊 HEAR ON", "🔇 HEAR OFF");
            command(SessionService.ACTION_LISTEN, listening);
        });

        babyMicButton = compactSecondary("");
        applyRemoteBabyStateStyle(babyMicButton, babyMicOn, babySettingsKnown,
                "🎙 BABY MIC ON", "🔇 BABY MIC OFF", "BABY MIC …");
        babyMicButton.setOnClickListener(v ->
                command(SessionService.ACTION_REMOTE_BABY_MIC, !babyMicOn));

        remoteVideoButton = compactSecondary("");
        applyRemoteBabyStateStyle(remoteVideoButton, babyCameraOn, babySettingsKnown,
                "📹 BABY CAM ON", "🚫 BABY CAM OFF", "BABY CAM …");
        remoteVideoButton.setOnClickListener(v ->
                command(SessionService.ACTION_REMOTE_BABY_CAMERA, !babyCameraOn));

        row1.addView(listenButton, compactButtonLp());
        row1.addView(babyMicButton, compactButtonLp());
        row1.addView(remoteVideoButton, compactButtonLp());
        panel.addView(row1, lp(-1,-2,0,0,0,2));

        LinearLayout row2 = row();
        row2.setGravity(Gravity.CENTER);

        remoteCameraButton = compactSecondary("↻ SWITCH BABY CAMERA");
        remoteCameraButton.setOnClickListener(v -> {
            command(SessionService.ACTION_REMOTE_SWITCH_CAMERA, true);
            if (devDummySession) {
                Toast.makeText(this, "DEV • dummy baby camera switched",
                        Toast.LENGTH_SHORT).show();
            }
        });

        Button sleep = compactSecondary(
                sleepingBabyUi ? "☀ EXIT SLEEPING" : "SLEEPING BABY");
        sleep.setOnClickListener(v -> {
            boolean next = !sleepingBabyUi;
            sleepingBabyUi = next;
            SessionBus.sleepingBaby = next;
            if (next) {
                listening = false;
                remoteVideoOn = false;
            } else {
                listening = true;
                remoteVideoOn = babyCameraOn;
            }
            command(SessionService.ACTION_SLEEPING_MODE, next);
            exitVideoFullscreenInPlace();
            showSession(SessionBus.code, activeHost);
        });

        row2.addView(remoteCameraButton, compactButtonLp());
        row2.addView(sleep, compactButtonLp());
        panel.addView(row2, lp(-1,-2,0,0,0,2));

        LinearLayout row3 = row();
        row3.setGravity(Gravity.CENTER);

        babyTorchButton = compactSecondary(
                babyAuxKnown
                        ? (babyTorchOn ? "🔦 LIGHT ON" : "🔦 LIGHT OFF")
                        : "🔦 LIGHT …");
        babyTorchButton.setOnClickListener(v ->
                command(SessionService.ACTION_REMOTE_BABY_TORCH, !babyTorchOn));

        babyBrightnessButton = compactSecondary(
                babyAuxKnown
                        ? (babyBrightnessBoost ? "☀ BRIGHT ON" : "☀ BRIGHT OFF")
                        : "☀ BRIGHT …");
        babyBrightnessButton.setOnClickListener(v ->
                command(SessionService.ACTION_REMOTE_BABY_BRIGHTNESS,
                        !babyBrightnessBoost));

        row3.addView(babyTorchButton, compactButtonLp());
        row3.addView(babyBrightnessButton, compactButtonLp());
        panel.addView(row3, lp(-1,-2,0,0,0,2));

        LinearLayout row4 = row();
        row4.setGravity(Gravity.CENTER);

        pttButton = compactPrimary("HOLD TO TALK");
        applyTalkButtonState(pttButton, false, false);
        pttButton.setOnTouchListener((v,e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                command(SessionService.ACTION_PTT, true);
                applyTalkButtonState(pttButton, true, false);
                return true;
            }
            if (e.getAction() == MotionEvent.ACTION_UP
                    || e.getAction() == MotionEvent.ACTION_CANCEL) {
                command(SessionService.ACTION_PTT, false);
                applyTalkButtonState(pttButton, false, false);
                return true;
            }
            return false;
        });

        Button fullscreenChat = compactSecondary("💬 CHAT");
        fullscreenChat.setOnClickListener(v -> showChatDialog());

        Button battery = compactSecondary("🔋 BATTERY");
        battery.setOnClickListener(v -> {
            try {
                startActivity(new Intent(
                        Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        Button disconnect = compactDanger("⏻ DISCONNECT");
        disconnect.setOnClickListener(v -> {
            command(SessionService.ACTION_DISCONNECT, true);
            videoFullscreenActive = false;
            showJoinLobby();
        });

        row4.addView(pttButton, compactButtonLp());
        row4.addView(fullscreenChat, compactButtonLp());
        row4.addView(battery, compactButtonLp());
        row4.addView(disconnect, compactButtonLp());
        panel.addView(row4, lp(-1,-2,0,0,0,0));

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                -1,-2,Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        controlsLp.setMargins(dp(8),dp(8),dp(8),dp(12));
        frame.addView(panel, controlsLp);

        Button back = compactSecondary("← BACK");
        back.setContentDescription("Back to Parent Station controls");
        back.setOnClickListener(v -> exitVideoFullscreenInPlace());
        fullscreenBackButton = back;
        FrameLayout.LayoutParams backLp = new FrameLayout.LayoutParams(
                -2,-2,Gravity.START | Gravity.TOP);
        backLp.setMargins(dp(10),dp(10),dp(10),dp(10));
        frame.addView(back, backLp);
    }

    private void showFullscreenVideoSession(String code, boolean host) {
        QuietLog.log("UI", "video_fullscreen_show",
                "orientation=" + getResources().getConfiguration().orientation);
        root = null;
        status = null;
        verification = null;
        levelText = null;
        chatButton = null;
        pipPresentation = false;

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        fullscreenVideoRoot = frame;

        if (!devDummySession) attachRemoteVideoTexture(frame);

        remoteVideo = new ImageView(this);
        remoteVideo.setBackgroundColor(Color.TRANSPARENT);
        remoteVideo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(remoteVideo, new FrameLayout.LayoutParams(-1,-1));

        videoStateOverlay = text(remoteVideoOn ? "WAITING FOR VIDEO" : "VIDEO OFF",
                10, Color.WHITE, true);
        videoStateOverlay.setPadding(dp(8),dp(5),dp(8),dp(5));
        videoStateOverlay.setBackground(makeRound(Color.argb(180, 20,22,25),10));
        FrameLayout.LayoutParams stateLp = new FrameLayout.LayoutParams(-2,-2,
                Gravity.START | Gravity.TOP);
        stateLp.setMargins(dp(10),dp(10),dp(10),dp(10));
        frame.addView(videoStateOverlay, stateLp);

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        previewFrame.setElevation(dp(10));
        fullscreenLocalPreviewFrame = previewFrame;
        if (!devDummySession) attachLocalVideoTexture(previewFrame);

        localPreview = new ImageView(this);
        localPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        localPreview.setBackgroundColor(Color.TRANSPARENT);
        previewFrame.addView(localPreview, new FrameLayout.LayoutParams(-1,-1));
        frame.addView(previewFrame, makeFullscreenPreviewLayoutParams());

        android.graphics.Bitmap shownRemote = devDummySession
                ? makeDummyVideo(devDummyDarkRoom, false)
                : SessionBus.latestVideo;
        android.graphics.Bitmap shownLocal = devDummySession
                ? (localVideoOn ? makeDummyVideo(false, true) : null)
                : SessionBus.latestLocalVideo;
        if (shownRemote != null) remoteVideo.setImageBitmap(shownRemote);
        if (shownLocal != null) {
            localPreview.setImageBitmap(shownLocal);
            localPreview.setVisibility(View.VISIBLE);
        } else {
            localPreview.setVisibility(View.GONE);
        }

        LinearLayout controls = row();
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(8),dp(6),dp(8),dp(6));
        controls.setBackground(makeRound(Color.argb(225, 25,27,30),32));
        controls.setElevation(dp(14));
        floatingVideoControls = controls;

        ImageButton cameraButton = floatingIconButton(
                R.drawable.ql_ic_video, "Turn your camera on or off", false);
        cameraButton.setAlpha(localVideoOn ? 1f : 0.45f);
        cameraButton.setOnClickListener(v -> {
            if (!devDummySession &&
                    checkSelfPermission(Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                requestVideoCameraPermission();
                return;
            }
            localVideoOn = !localVideoOn;
            cameraButton.setAlpha(localVideoOn ? 1f : 0.45f);
            command(SessionService.ACTION_LOCAL_VIDEO, localVideoOn);
        });

        ImageButton switchButton = floatingIconButton(
                R.drawable.ql_ic_switch_camera, "Switch camera", false);
        switchButton.setOnClickListener(v -> {
            if (devDummySession) return;
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {
                command(SessionService.ACTION_LOCAL_SWITCH_CAMERA, true);
            } else {
                requestVideoCameraPermission();
            }
        });

        ImageButton micButton = floatingIconButton(
                R.drawable.ql_ic_mic, "Mute or unmute microphone", false);
        micButton.setAlpha(micMuted ? 0.45f : 1f);
        micButton.setOnClickListener(v -> {
            micMuted = !micMuted;
            micButton.setAlpha(micMuted ? 0.45f : 1f);
            command(SessionService.ACTION_MIC_MUTE, micMuted);
        });

        ImageButton disconnectButton = floatingIconButton(
                R.drawable.ql_ic_call_end, "Disconnect", true);
        disconnectButton.setOnClickListener(v -> {
            command(SessionService.ACTION_DISCONNECT, true);
            showJoinLobby();
        });

        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(46),dp(46));
        iconLp.setMargins(dp(5),0,dp(5),0);
        controls.addView(cameraButton, new LinearLayout.LayoutParams(iconLp));
        controls.addView(switchButton, new LinearLayout.LayoutParams(iconLp));
        controls.addView(micButton, new LinearLayout.LayoutParams(iconLp));
        controls.addView(disconnectButton, new LinearLayout.LayoutParams(iconLp));

        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(
                -2, dp(60), Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        controlsLp.setMargins(dp(12),dp(12),dp(12),dp(18));
        frame.addView(controls, controlsLp);

        frame.setOnClickListener(v ->
                setFullscreenVideoControlsVisible(!videoControlsVisible));

        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            int topInset = Math.max(insets.getSystemWindowInsetTop(),
                    insets.getStableInsetTop());
            int bottomInset = Math.max(insets.getSystemWindowInsetBottom(),
                    insets.getStableInsetBottom());

            FrameLayout.LayoutParams c = (FrameLayout.LayoutParams) controls.getLayoutParams();
            c.bottomMargin = dp(14) + bottomInset;
            controls.setLayoutParams(c);

            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) previewFrame.getLayoutParams();
            p.topMargin = dp(10) + topInset;
            previewFrame.setLayoutParams(p);
            return insets;
        });

        setContentView(frame);
        frame.requestApplyInsets();
        videoControlsVisible = true;
        setFullscreenVideoControlsVisible(true);
        requestVideoWakeRefresh();
    }

    private boolean localPreviewIsPortrait() {
        int r = RotationLabConfig.enabled(this)
                ? RotationLabConfig.resolveLocalPreviewRotation(
                    this, SessionBus.localVideoRotation)
                : RotationLabConfig.normalize(SessionBus.localVideoRotation);
        return r == 90 || r == 270;
    }

    private FrameLayout.LayoutParams makeInlinePreviewLayoutParams() {
        boolean portrait = localPreviewIsPortrait();
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                dp(portrait ? 90 : 160),
                dp(portrait ? 160 : 90),
                Gravity.END | Gravity.BOTTOM);
        p.setMargins(dp(8),dp(8),dp(8),dp(8));
        return p;
    }

    private FrameLayout.LayoutParams makeFullscreenPreviewLayoutParams() {
        boolean portrait = localPreviewIsPortrait();
        int width = dp(portrait ? 90 : 160);
        int height = dp(portrait ? 160 : 90);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                width, height, Gravity.END | Gravity.TOP);
        p.setMargins(dp(10),dp(10),dp(10),dp(10));
        return p;
    }

    private void updateLocalPreviewLayout() {
        FrameLayout preview = fullscreenLocalPreviewFrame;
        if (preview == null || preview.getParent() == null) return;

        boolean full = videoFullscreenActive
                || (fullscreenVideoRoot != null && root == null);
        FrameLayout.LayoutParams old =
                (FrameLayout.LayoutParams) preview.getLayoutParams();
        FrameLayout.LayoutParams fresh = full
                ? makeFullscreenPreviewLayoutParams()
                : makeInlinePreviewLayoutParams();

        if (full) fresh.topMargin = old.topMargin;
        preview.setLayoutParams(fresh);
    }

    private ImageButton floatingIconButton(int drawableRes,
                                           String description,
                                           boolean danger) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawableRes);
        b.setColorFilter(Color.WHITE);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        b.setPadding(dp(11),dp(11),dp(11),dp(11));
        b.setContentDescription(description);
        b.setBackground(makeRound(
                danger ? Color.rgb(244,67,64) : Color.rgb(52,54,58), 30));
        b.setFocusable(true);
        return b;
    }

    private void setFullscreenVideoControlsVisible(boolean visible) {
        videoControlsVisible = visible;
        if (floatingVideoControls != null) {
            floatingVideoControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (fullscreenBackButton != null) {
            fullscreenBackButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        if (videoStateOverlay != null) {
            videoStateOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                if (visible) {
                    c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                } else {
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        } else {
            int flags = visible
                    ? View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    : View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void showChatDialog() {
        if (!SessionBus.connected && !devDummySession) return;
        if (chatDialog != null && chatDialog.isShowing()) return;
        if (!devDummySession) markChatReadAndClearNotification();

        LinearLayout box = column();
        box.setPadding(dp(12),dp(8),dp(12),dp(8));

        TextView note = text("Messages exist only for this connection and are cleared when it ends.", 11, muted(), false);
        box.addView(note, lp(-1,-2,0,0,0,6));

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(true);
        chatMessageList = column();
        chatMessageList.setPadding(dp(4),dp(4),dp(4),dp(4));
        chatScroll.addView(chatMessageList);
        box.addView(chatScroll, new LinearLayout.LayoutParams(-1,dp(280)));

        LinearLayout composer = row();
        EditText input = new EditText(this);
        input.setHint("Message");
        input.setSingleLine(false);
        input.setMaxLines(4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(1000)});
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        composer.addView(input, new LinearLayout.LayoutParams(0,dp(56),1f));

        Button send = primary("SEND");
        send.setOnClickListener(v -> {
            String message = input.getText().toString().trim();
            if (message.isEmpty()) return;
            if (devDummySession) {
                devChatMessages.add(new SessionBus.ChatMessage(true, message, System.currentTimeMillis()));
                input.setText("");
                renderChatMessages(new ArrayList<>(devChatMessages));
                String reply = "Dummy reply • " + message;
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (!devDummySession) return;
                    devChatMessages.add(new SessionBus.ChatMessage(false, reply, System.currentTimeMillis()));
                    renderChatMessages(new ArrayList<>(devChatMessages));
                }, 350);
            } else {
                Intent i = new Intent(this, SessionService.class)
                        .setAction(SessionService.ACTION_SEND_CHAT)
                        .putExtra(SessionService.EXTRA_CHAT_TEXT, message);
                startService(i);
                input.setText("");
            }
        });
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(82),dp(48));
        sendLp.setMargins(dp(6),dp(4),0,0);
        composer.addView(send, sendLp);
        box.addView(composer, lp(-1,-2,0,6,0,0));

        chatDialog = new android.app.AlertDialog.Builder(this)
                .setTitle("QuietLink chat")
                .setView(box)
                .setNegativeButton("Close", null)
                .create();
        chatDialog.setOnDismissListener(d -> {
            chatDialog = null;
            chatMessageList = null;
            chatScroll = null;
        });
        chatDialog.setOnShowListener(d -> renderChatMessages(
                devDummySession ? new ArrayList<>(devChatMessages) : SessionBus.chatSnapshot()));
        chatDialog.show();
    }

    private void renderChatMessages(List<SessionBus.ChatMessage> messages) {
        if (chatMessageList == null) return;
        chatMessageList.removeAllViews();

        String peer = devDummySession ? "Dummy Device"
                : ((SessionBus.peerName == null || SessionBus.peerName.trim().isEmpty())
                    ? "Other device" : SessionBus.peerName.trim());

        if (messages == null || messages.isEmpty()) {
            TextView empty = text("No messages yet.", 13, muted(), false);
            empty.setGravity(Gravity.CENTER);
            chatMessageList.addView(empty, lp(-1,dp(64),0,8,0,0));
        } else {
            for (SessionBus.ChatMessage message : messages) {
                LinearLayout bubble = column();
                bubble.setPadding(dp(10),dp(7),dp(10),dp(7));
                bubble.setBackground(makeRound(message.mine ? panel2() : panelColor(),12));

                String who = message.mine ? "You" : peer;
                String stamp = android.text.format.DateFormat.getTimeFormat(this)
                        .format(new java.util.Date(message.timeMs));
                LinearLayout meta = row();
                meta.addView(text(who, 10, message.mine ? accent() : Color.LTGRAY, true),
                        new LinearLayout.LayoutParams(0,-2,1f));
                TextView time = text(stamp, 9, muted(), false);
                time.setGravity(Gravity.END);
                meta.addView(time, new LinearLayout.LayoutParams(-2,-2));
                bubble.addView(meta, lp(-1,-2,0,0,0,0));

                TextView body = text(message.text, 14, Color.WHITE, false);
                body.setTextIsSelectable(false);
                bubble.addView(body, lp(-1,-2,0,2,0,0));

                LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(-1,-2);
                bubbleLp.setMargins(message.mine ? dp(36) : 0,dp(3),message.mine ? 0 : dp(36),dp(3));
                chatMessageList.addView(bubble,bubbleLp);
            }
        }

        if (chatScroll != null) {
            chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void updateChatButtonBadge() {
        Button b = chatButton;
        if (b == null) return;
        int unread = devDummySession ? 0 : SessionBus.unreadChatCount();
        if (unread > 0) {
            String count = unread > 9 ? "9+" : Integer.toString(unread);
            b.setText("💬 " + count);
            b.setTextSize(15);
            b.setTextColor(Color.WHITE);
            b.setBackground(makeRound(Color.rgb(145,38,50),14));
            b.setContentDescription(unread + (unread == 1 ? " unread message" : " unread messages"));
        } else {
            b.setText("💬");
            b.setTextSize(20);
            b.setTextColor(Color.WHITE);
            b.setBackground(makeRound(panel2(),14));
            b.setContentDescription("Open text chat");
        }
    }

    private void markChatReadAndClearNotification() {
        if (devDummySession) return;
        SessionBus.markChatRead();
        updateChatButtonBadge();
        try {
            startService(new Intent(this, SessionService.class)
                    .setAction(SessionService.ACTION_CHAT_READ));
        } catch (Exception ignored) {}
    }

    private void confirmBabyRoleSwap() {
        if (activeMode != SessionService.MODE_BABY) return;
        String target = activeBabyStation ? "Parent Station" : "Baby Station";
        String detail = activeBabyStation
                ? "This phone will stop continuously sending its baby microphone/camera and become the monitoring Parent Station."
                : "This phone will become the Baby Station and may continuously send microphone/camera audio/video according to the current controls.";

        new android.app.AlertDialog.Builder(this)
                .setTitle("Swap to " + target + "?")
                .setMessage(detail + "\n\nThis prevents an accidental tap from silently swapping roles.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("SWAP", (dialog, which) -> {
                    QuietLog.log("UI", "baby_role_swap_confirm",
                            "from=" + (activeBabyStation ? "baby" : "parent")
                                    + " to=" + (activeBabyStation ? "parent" : "baby"));
                    requestBabyRoleSwap();
                })
                .show();
    }

    private void requestBabyRoleSwap() {
        if (activeMode != SessionService.MODE_BABY) return;
        if (devDummySession) {
            activeBabyStation = !activeBabyStation;
            sleepingBabyUi = false;
            SessionBus.sleepingBaby = false;
            listening = true;
            remoteVideoOn = true;
            showSession("", true);
            return;
        }

        // Becoming the Baby Station requires a camera. Switching from Baby to
        // Parent does not.
        if (!activeBabyStation &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingBabyRoleSwap = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 46);
            return;
        }

        startService(new Intent(this, SessionService.class)
                .setAction(SessionService.ACTION_SWAP_BABY_ROLE));
    }

    private void requestModeChange(int newMode) {
        if (newMode == SessionService.MODE_BABY && !activeHost) return;
        if (devDummySession) {
            activeMode = newMode;
            sleepingBabyUi = false;
            SessionBus.sleepingBaby = false;
            activeBabyStation = false;
            listening = true;
            remoteVideoOn = true;
            localVideoOn = true;
            showSession("", true);
            return;
        }
        if (newMode != SessionService.MODE_VOICE &&
                checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingHostModeSwitch = newMode;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 44);
            return;
        }
        commandMode(newMode);
    }

    private void commandMode(int newMode) {
        Intent i = new Intent(this, SessionService.class)
                .setAction(SessionService.ACTION_SET_MODE)
                .putExtra(SessionService.EXTRA_NEW_MODE, newMode);
        startService(i);
    }

    private void configurePictureInPicture(boolean enabled) {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(currentPictureInPictureRatio());
            if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(enabled);
            setPictureInPictureParams(builder.build());
        } catch (Exception ignored) {}
    }

    private Rational currentPictureInPictureRatio() {
        android.graphics.Bitmap bitmap = devDummySession
                ? makeDummyVideo(devDummyDarkRoom, false)
                : (SessionBus.latestVideo != null ? SessionBus.latestVideo : SessionBus.latestLocalVideo);
        if (bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
            return safePictureInPictureRatio(bitmap.getWidth(), bitmap.getHeight());
        }

        // Until the first frame arrives, use the current physical display shape
        // rather than forcing a landscape 16:9 window.
        int w = getResources().getDisplayMetrics().widthPixels;
        int h = getResources().getDisplayMetrics().heightPixels;
        return safePictureInPictureRatio(Math.max(1, w), Math.max(1, h));
    }

    private Rational safePictureInPictureRatio(int width, int height) {
        double ratio = width / (double)Math.max(1, height);
        // Android constrains PiP aspect ratios. Keep the real orientation and
        // only clamp unusually extreme camera shapes.
        if (ratio < 0.42) return new Rational(42, 100);
        if (ratio > 2.38) return new Rational(238, 100);
        return new Rational(Math.max(1, width), Math.max(1, height));
    }

    private void updatePictureInPictureAspect(android.graphics.Bitmap bitmap) {
        if (Build.VERSION.SDK_INT < 26 || bitmap == null || activeMode == SessionService.MODE_VOICE) return;
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        if (w < 1 || h < 1 || (w == lastPipWidth && h == lastPipHeight)) return;
        lastPipWidth = w;
        lastPipHeight = h;
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(safePictureInPictureRatio(w, h));
            if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(true);
            setPictureInPictureParams(builder.build());
        } catch (Exception ignored) {}
    }

    private void enterQuietLinkPictureInPicture() {
        if (Build.VERSION.SDK_INT < 26 || (!devDummySession && (!SessionBus.connected || !SessionBus.active))) return;
        if (activeMode == SessionService.MODE_VOICE || isInPictureInPictureMode()) return;

        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(currentPictureInPictureRatio());
            enterPictureInPictureMode(builder.build());
        } catch (Exception ignored) {}
    }

    private void showPictureInPictureVideo() {
        releaseVideoSurfaces();
        pipPresentation = true;
        configurePictureInPicture(true);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);

        if (!devDummySession) attachRemoteVideoTexture(frame);

        remoteVideo = new ImageView(this);
        remoteVideo.setBackgroundColor(Color.TRANSPARENT);
        remoteVideo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(remoteVideo, new FrameLayout.LayoutParams(-1,-1));

        android.graphics.Bitmap primary = devDummySession
                ? makeDummyVideo(devDummyDarkRoom, false)
                : (SessionBus.latestVideo != null ? SessionBus.latestVideo : SessionBus.latestLocalVideo);
        if (primary != null) remoteVideo.setImageBitmap(primary);

        localPreview = null;
        localVideoTexture = null;
        setContentView(frame);
    }

    private void showBlackScreen(String code, boolean host) {
        releaseVideoSurfaces();
        renderedSession = true;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams a = getWindow().getAttributes();
        a.screenBrightness = 0.01f; getWindow().setAttributes(a);
        FrameLayout black = new FrameLayout(this); black.setBackgroundColor(Color.BLACK);
        TextView hint = text("QuietLink monitoring\nTap to wake screen", 12, Color.rgb(28,28,28), false);
        hint.setGravity(Gravity.CENTER);
        black.addView(hint, new FrameLayout.LayoutParams(-1,-1));
        black.setOnClickListener(v -> showSession(code, host));
        setContentView(black);
    }

    private void attachRemoteVideoTexture(FrameLayout parent) {
        remoteVideoTexture = new TextureView(this);
        remoteVideoTexture.setOpaque(true);
        parent.addView(remoteVideoTexture, new FrameLayout.LayoutParams(-1,-1));
        remoteVideoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture, int width, int height) {
                QuietLog.log("SURFACE", "remote_available",
                        "view=" + width + "x" + height);
                texture.setDefaultBufferSize(H264Codec.WIDTH, H264Codec.HEIGHT);
                Surface surface = new Surface(texture);
                Surface old = remoteDecodeSurface;
                remoteDecodeSurface = surface;
                if (old != null && old != surface) {
                    try { old.release(); } catch (Exception ignored) {}
                }
                SessionBus.remoteVideoSurface(surface);
                applyVideoTextureTransform(remoteVideoTexture, SessionBus.remoteVideoRotation, false, false);
            }

            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture texture, int width, int height) {
                applyVideoTextureTransform(remoteVideoTexture, SessionBus.remoteVideoRotation, false, false);
            }

            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture texture) {
                QuietLog.log("SURFACE", "remote_destroyed", "");
                Surface surface = remoteDecodeSurface;
                if (surface != null) {
                    if (SessionBus.remoteVideoSurface == surface) SessionBus.remoteVideoSurface(null);
                    try { surface.release(); } catch (Exception ignored) {}
                    remoteDecodeSurface = null;
                }
                return true;
            }

            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture) {}
        });
    }

    private void attachLocalVideoTexture(FrameLayout parent) {
        localVideoTexture = new TextureView(this);
        localVideoTexture.setOpaque(false);
        parent.addView(localVideoTexture, new FrameLayout.LayoutParams(-1,-1));
        localVideoTexture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture texture, int width, int height) {
                QuietLog.log("SURFACE", "local_available",
                        "view=" + width + "x" + height);
                texture.setDefaultBufferSize(H264Codec.WIDTH, H264Codec.HEIGHT);
                Surface surface = new Surface(texture);
                Surface old = localCameraSurface;
                localCameraSurface = surface;
                if (old != null && old != surface) {
                    try { old.release(); } catch (Exception ignored) {}
                }
                SessionBus.localVideoSurface(surface);
                applyVideoTextureTransform(localVideoTexture, SessionBus.localVideoRotation, true, false);
            }

            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture texture, int width, int height) {
                applyVideoTextureTransform(localVideoTexture, SessionBus.localVideoRotation, true, true);
            }

            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture texture) {
                QuietLog.log("SURFACE", "local_destroyed", "");
                Surface surface = localCameraSurface;
                if (surface != null) {
                    if (SessionBus.localVideoSurface == surface) SessionBus.localVideoSurface(null);
                    try { surface.release(); } catch (Exception ignored) {}
                    localCameraSurface = null;
                }
                return true;
            }

            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture texture) {}
        });
    }

    private void releaseVideoSurfaces() {
        Surface remote = remoteDecodeSurface;
        if (remote != null) {
            if (SessionBus.remoteVideoSurface == remote) SessionBus.remoteVideoSurface(null);
            try { remote.release(); } catch (Exception ignored) {}
            remoteDecodeSurface = null;
        }
        Surface local = localCameraSurface;
        if (local != null) {
            if (SessionBus.localVideoSurface == local) SessionBus.localVideoSurface(null);
            try { local.release(); } catch (Exception ignored) {}
            localCameraSurface = null;
        }
        remoteVideoTexture = null;
        localVideoTexture = null;
        fullscreenVideoRoot = null;
        fullscreenLocalPreviewFrame = null;
        activeInlineVideoFrame = null;
        floatingVideoControls = null;
        fullscreenBackButton = null;
        fullscreenHiddenViews.clear();
        fullscreenHiddenVisibility.clear();
    }

    private void applyVideoTextureTransform(TextureView view,
                                            int rotation,
                                            boolean mirror,
                                            boolean crop) {
        if (view == null) return;
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return;

        int r = ((rotation % 360) + 360) % 360;
        // Production defaults preserve the existing behavior. When the hidden
        // rotation lab is enabled, the local TextureView can be decoupled from
        // transmitted frame rotation so Android's display-only strategy can be
        // tested independently.
        int drawRotation = r;
        boolean drawMirror = mirror;
        if (mirror && RotationLabConfig.enabled(this)) {
            drawRotation = RotationLabConfig.resolveLocalPreviewRotation(this, r);
            drawMirror = RotationLabConfig.mirrorLocalPreview(this)
                    && SessionBus.localCameraFront;
        }
        if (mirror) {
            QuietLog.log("UI", "local_preview_transform",
                    "view=" + width + "x" + height
                            + " rotation=" + drawRotation
                            + " mirror=" + (drawMirror ? 1 : 0)
                            + " front=" + (SessionBus.localCameraFront ? 1 : 0));
        }
        boolean quarterTurn = drawRotation == 90 || drawRotation == 270;
        float effectiveSourceWidth = quarterTurn ? H264Codec.HEIGHT : H264Codec.WIDTH;
        float effectiveSourceHeight = quarterTurn ? H264Codec.WIDTH : H264Codec.HEIGHT;

        float scale = crop
                ? Math.max(width / effectiveSourceWidth, height / effectiveSourceHeight)
                : Math.min(width / effectiveSourceWidth, height / effectiveSourceHeight);
        float displayedWidth = effectiveSourceWidth * scale;
        float displayedHeight = effectiveSourceHeight * scale;
        float preRotateWidth = quarterTurn ? displayedHeight : displayedWidth;
        float preRotateHeight = quarterTurn ? displayedWidth : displayedHeight;

        float cx = width / 2f;
        float cy = height / 2f;
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        matrix.setScale(preRotateWidth / width, preRotateHeight / height, cx, cy);
        matrix.postRotate(drawRotation, cx, cy);
        if (drawMirror) matrix.postScale(-1f, 1f, cx, cy);

        view.setRotation(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTransform(matrix);
    }

    private void updateH264PictureInPictureAspect() {
        if (Build.VERSION.SDK_INT < 26 || activeMode == SessionService.MODE_VOICE) return;
        int r = ((SessionBus.remoteVideoRotation % 360) + 360) % 360;
        int w = (r == 90 || r == 270) ? H264Codec.HEIGHT : H264Codec.WIDTH;
        int h = (r == 90 || r == 270) ? H264Codec.WIDTH : H264Codec.HEIGHT;
        if (w == lastPipWidth && h == lastPipHeight) return;
        lastPipWidth = w;
        lastPipHeight = h;
        try {
            PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder()
                    .setAspectRatio(safePictureInPictureRatio(w, h));
            if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(true);
            setPictureInPictureParams(builder.build());
        } catch (Exception ignored) {}
    }

    private void resetBrightness() {
        removeBabyWhiteLightOverlay();
        restoreBabyWhiteLightWindowUi();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams a = getWindow().getAttributes();
        a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(a);
    }

    private void applyBabyBrightnessBoost(boolean enabled) {
        if (enabled) {
            if (!babyWhiteLightUiSaved) {
                View decor = getWindow().getDecorView();
                babyWhiteLightSavedSystemUi = decor.getSystemUiVisibility();
                babyWhiteLightSavedStatusColor = getWindow().getStatusBarColor();
                babyWhiteLightSavedNavColor = getWindow().getNavigationBarColor();
                if (Build.VERSION.SDK_INT >= 28) {
                    babyWhiteLightSavedNavDividerColor =
                            getWindow().getNavigationBarDividerColor();
                }
                babyWhiteLightUiSaved = true;
            }

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.screenBrightness = 1.0f;
            getWindow().setAttributes(a);

            getWindow().setStatusBarColor(Color.WHITE);
            getWindow().setNavigationBarColor(Color.WHITE);
            if (Build.VERSION.SDK_INT >= 28) {
                getWindow().setNavigationBarDividerColor(Color.WHITE);
            }

            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

            FrameLayout content = findViewById(android.R.id.content);
            if (babyWhiteLightOverlay == null) {
                babyWhiteLightOverlay = new View(this);
                babyWhiteLightOverlay.setBackgroundColor(Color.WHITE);
                babyWhiteLightOverlay.setClickable(true);
                babyWhiteLightOverlay.setFocusable(true);
                babyWhiteLightOverlay.setContentDescription("Baby Station white room light");
                babyWhiteLightOverlay.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            if (babyWhiteLightOverlay.getParent() == null && content != null) {
                content.addView(babyWhiteLightOverlay,
                        new FrameLayout.LayoutParams(-1,-1));
            }
            babyWhiteLightOverlay.bringToFront();
        } else {
            WindowManager.LayoutParams a = getWindow().getAttributes();
            a.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(a);
            removeBabyWhiteLightOverlay();
            restoreBabyWhiteLightWindowUi();
        }

        QuietLog.log("UI", "baby_brightness_apply",
                "enabled=" + (enabled ? 1 : 0)
                        + " white_overlay=" + (enabled ? 1 : 0));
    }

    private void removeBabyWhiteLightOverlay() {
        if (babyWhiteLightOverlay != null
                && babyWhiteLightOverlay.getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) babyWhiteLightOverlay.getParent())
                        .removeView(babyWhiteLightOverlay);
            } catch (Exception ignored) {}
        }
    }

    private void restoreBabyWhiteLightWindowUi() {
        if (!babyWhiteLightUiSaved) return;
        getWindow().getDecorView().setSystemUiVisibility(
                babyWhiteLightSavedSystemUi);
        getWindow().setStatusBarColor(babyWhiteLightSavedStatusColor);
        getWindow().setNavigationBarColor(babyWhiteLightSavedNavColor);
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().setNavigationBarDividerColor(
                    babyWhiteLightSavedNavDividerColor);
        }
        babyWhiteLightUiSaved = false;
    }

    private void command(String action, boolean value) {
        if (devDummySession) {
            if (SessionService.ACTION_SLEEPING_MODE.equals(action)) {
                sleepingBabyUi = value;
                SessionBus.sleepingBaby = value;
                if (value) {
                    listening = false;
                    babyCameraOn = false;
                    remoteVideoOn = false;
                } else {
                    babyCameraOn = true;
                    remoteVideoOn = true;
                }
                babySettingsKnown = true;
            } else if (SessionService.ACTION_LISTEN.equals(action)) {
                listening = value;
            } else if (SessionService.ACTION_REMOTE_VIDEO.equals(action)) {
                remoteVideoOn = value;
            } else if (SessionService.ACTION_REMOTE_BABY_MIC.equals(action)) {
                babyMicOn = value;
                babySettingsKnown = true;
                if (renderedSession) showSession("", true);
            } else if (SessionService.ACTION_REMOTE_BABY_CAMERA.equals(action)) {
                babyCameraOn = value;
                babySettingsKnown = true;
                remoteVideoOn = value;
                if (renderedSession) showSession("", true);
            } else if (SessionService.ACTION_LOCAL_VIDEO.equals(action)) {
                localVideoOn = value;
            } else if (SessionService.ACTION_MIC_MUTE.equals(action)) {
                micMuted = value;
            } else if (SessionService.ACTION_DISCONNECT.equals(action)) {
                stopDummySessionState();
            }
            return;
        }
        Intent i = new Intent(this, SessionService.class).setAction(action).putExtra(SessionService.EXTRA_VALUE, value);
        startService(i);
    }

    private void commandLevel(String action, float value) {
        if (devDummySession) {
            if (SessionService.ACTION_SENSITIVITY.equals(action)) soundThresholdUi = value;
            else if (SessionService.ACTION_OUTPUT_VOLUME.equals(action)) receiveVolume = value;
            return;
        }
        Intent i = new Intent(this, SessionService.class).setAction(action).putExtra(SessionService.EXTRA_LEVEL, value);
        startService(i);
    }

    private void requestBasePermissions() {
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (!p.isEmpty()) requestPermissions(p.toArray(new String[0]), 41);
    }

    private List<String> missingPermissions(int mode, boolean isHost) {
        List<String> p = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.RECORD_AUDIO);
        if (isHost && mode != SessionService.MODE_VOICE && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.CAMERA);
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            if (mode == SessionService.MODE_SLEEPING_BABY && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.POST_NOTIFICATIONS);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        return p;
    }

    private void requestBabyRoleCameraPermission() {
        if (babyRoleCameraRequestInFlight ||
                checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return;
        babyRoleCameraRequestInFlight = true;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, 47);
    }

    private void requestVideoCameraPermission() {
        if (cameraRequestInFlight || checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) command(SessionService.ACTION_LOCAL_VIDEO, true);
            return;
        }
        cameraRequestInFlight = true;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, 43);
    }

    private String randomCode() { return String.format("%06d", new SecureRandom().nextInt(1_000_000)); }
    private String formatCode(String c) { return c != null && c.length() == 6 ? c.substring(0,3) + " " + c.substring(3) : c; }

    @Override public void onStatus(String s) {
        runOnUiThread(() -> {
            if (SessionBus.active && restoringPersistedSession && !renderedSession) {
                restoringPersistedSession = false;
                activeMode = SessionBus.mode;
                activeHost = SessionBus.host;
                activeBabyStation = SessionBus.babyStation;
                sleepingBabyUi = SessionBus.sleepingBaby;
                showSession(SessionBus.code, SessionBus.host);
            }
            if (status != null) status.setText(s);
            if (status == null && sleepingBabyUi && activeMode == SessionService.MODE_BABY
                    && !activeBabyStation && levelText != null) {
                if (s != null && s.contains("not responding")) {
                    levelText.setText("⚠  Baby Station not responding…");
                    levelText.setTextColor(Color.rgb(255,120,120));
                } else if (s != null && s.startsWith("Connected")) {
                    if (babySettingsKnown && !babyMicOn) {
                        levelText.setText("Baby mic OFF • sound alerts unavailable");
                        levelText.setTextColor(Color.rgb(255,170,170));
                    } else {
                        levelText.setText("Live room 0%  ▁▁▁▁▁▁▁▁");
                        levelText.setTextColor(Color.LTGRAY);
                    }
                }
            }
        });
    }

    @Override public void onModeChanged(int newMode, boolean isHost) {
        runOnUiThread(() -> {
            if (!SessionBus.active) return;
            boolean changed = activeMode != newMode || activeHost != isHost;
            boolean needsRestoreScreen = restoringPersistedSession && !renderedSession;
            if (needsRestoreScreen) restoringPersistedSession = false;
            activeMode = newMode; activeHost = isHost;
            if (newMode != SessionService.MODE_VIDEO
                    && newMode != SessionService.MODE_BABY) {
                videoFullscreenActive = false;
            }
            if (newMode != SessionService.MODE_BABY || !SessionBus.sleepingBaby) {
                remoteVideoOn = SessionBus.remoteVideoEnabled;
            }
            if (needsRestoreScreen) {
                sleepingBabyUi = SessionBus.sleepingBaby;
                activeBabyStation = SessionBus.babyStation;
                showSession(SessionBus.code, isHost);
            } else if (changed && renderedSession && !pipPresentation) {
                showSession(SessionBus.code, isHost);
            }
            if (newMode == SessionService.MODE_VIDEO && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestVideoCameraPermission();
            } else if (newMode == SessionService.MODE_BABY &&
                    checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestBabyRoleCameraPermission();
            }
        });
    }

    @Override public void onBabyRoleChanged(boolean babyStation) {
        runOnUiThread(() -> {
            boolean changed = activeBabyStation != babyStation;
            activeBabyStation = babyStation;
            if (changed) {
                sleepingBabyUi = SessionBus.sleepingBaby;
                remoteVideoOn = true;
                if (babyStation && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestBabyRoleCameraPermission();
                }
                if (renderedSession && !pipPresentation && activeMode == SessionService.MODE_BABY) {
                    showSession(SessionBus.code, activeHost);
                }
            }
        });
    }

    @Override public void onSleepingBabyChanged(boolean enabled) {
        runOnUiThread(() -> {
            sleepingBabyUi = enabled;
            if (renderedSession && !pipPresentation
                    && activeMode == SessionService.MODE_BABY) {
                showSession(SessionBus.code, activeHost);
            }
        });
    }

    @Override public void onBabyAuxStateChanged(boolean torchEnabled,
                                                boolean brightnessBoost,
                                                boolean known) {
        runOnUiThread(() -> {
            babyTorchOn = torchEnabled;
            babyBrightnessBoost = brightnessBoost;
            babyAuxKnown = known;

            if (activeMode == SessionService.MODE_BABY && activeBabyStation) {
                applyBabyBrightnessBoost(brightnessBoost);
            }

            // Update labels in place. Rebuilding showSession() here destroys both
            // TextureViews and can trigger multiple Camera2 restarts on older phones.
            if (babyTorchButton != null) {
                babyTorchButton.setText(known
                        ? (torchEnabled ? "🔦 LIGHT ON" : "🔦 LIGHT OFF")
                        : "🔦 LIGHT …");
            }
            if (babyBrightnessButton != null) {
                babyBrightnessButton.setText(known
                        ? (brightnessBoost ? "☀ BRIGHT ON" : "☀ BRIGHT OFF")
                        : "☀ BRIGHT …");
            }
        });
    }

    @Override public void onBabySettingsChanged(boolean micEnabled, boolean cameraEnabled, boolean known) {
        runOnUiThread(() -> {
            if (devDummySession) return;
            babyMicOn = micEnabled;
            babyCameraOn = cameraEnabled;
            babySettingsKnown = known;

            if (activeMode == SessionService.MODE_BABY && activeBabyStation && known) {
                micMuted = !micEnabled;
                localVideoOn = cameraEnabled;
            }
            if (activeMode == SessionService.MODE_BABY && !activeBabyStation) {
                remoteVideoOn = cameraEnabled;
            }

            if (babyStateText != null) {
                babyStateText.setText(known
                        ? "BABY STATION • MIC " + (micEnabled ? "ON" : "OFF")
                            + " • CAMERA " + (cameraEnabled ? "ON" : "OFF")
                        : "BABY STATION • checking remote state…");
                babyStateText.setTextColor(known ? Color.LTGRAY : muted());
            }
            if (babyMicButton != null) {
                applyRemoteBabyStateStyle(babyMicButton, micEnabled, known,
                        "🎙 BABY MIC ON", "🔇 BABY MIC OFF", "BABY MIC …");
            }
            if (remoteVideoButton != null) {
                applyRemoteBabyStateStyle(remoteVideoButton, cameraEnabled, known,
                        "📹 CAMERA ON", "🚫 CAMERA OFF", "CAMERA …");
            }
            if (babyOwnStateText != null) {
                babyOwnStateText.setText("Mic " + (micEnabled ? "ON" : "OFF")
                        + " • Camera " + (cameraEnabled ? "ON" : "OFF")
                        + (known ? "" : " • syncing"));
            }
            if (babyOwnCameraButton != null) {
                babyOwnCameraButton.setText(cameraEnabled ? "CAMERA ON" : "CAMERA OFF");
            }

            // Do not call showSession() for state sync. The video TextureViews
            // remain attached and the camera pipeline stays undisturbed.
        });
    }

    @Override public void onConnected(String v) {
        runOnUiThread(() -> {
            if (incomingDialog != null) {
                incomingDialog.dismiss();
                incomingDialog = null;
            }
            if (outgoingDialog != null) {
                outgoingDialog.dismiss();
                outgoingDialog = null;
            }
            activeMode = SessionBus.mode;
            activeHost = SessionBus.host;
            activeBabyStation = SessionBus.babyStation;
            micMuted = SessionBus.localMicMuted;
            babyMicOn = SessionBus.babyMicEnabled;
            babyCameraOn = SessionBus.babyCameraEnabled;
            babySettingsKnown = SessionBus.babySettingsKnown;
            babyTorchOn = SessionBus.babyTorchEnabled;
            babyBrightnessBoost = SessionBus.babyBrightnessBoost;
            babyAuxKnown = SessionBus.babyAuxKnown;
            remoteVideoOn = SessionBus.remoteVideoEnabled;
            if (!activeBabyStation && activeMode == SessionService.MODE_BABY && babySettingsKnown) {
                remoteVideoOn = babyCameraOn;
            }
            if (!renderedSession && SessionBus.active) showSession(SessionBus.code, SessionBus.host);
        });
    }

    @Override public void onAudioLevel(float l) {
        runOnUiThread(() -> {
            if (levelText != null) {
                if (activeMode == SessionService.MODE_BABY && !activeBabyStation
                        && babySettingsKnown && !babyMicOn) {
                    levelText.setText("Baby mic OFF • sound alerts unavailable");
                    levelText.setTextColor(Color.rgb(255,170,170));
                    return;
                }
                int n = Math.max(0, Math.min(8, (int)(l * 9)));
                StringBuilder b = new StringBuilder((activeMode == SessionService.MODE_BABY && sleepingBabyUi && !activeBabyStation ? "Live room level: " : "Room level ") + Math.round(l * 100f) + "%  ");
                for(int i=0;i<8;i++) b.append(i<n?'▇':'▁');
                levelText.setTextColor(Color.LTGRAY);
                levelText.setText(b.toString());
            }
        });
    }

    @Override public void onRemoteVideoEnabledChanged(boolean enabled) {
        runOnUiThread(() -> {
            remoteVideoOn = enabled;
            QuietLog.log("UI", "remote_video_state", "enabled=" + (enabled ? 1 : 0));
            if (remoteVideoTexture != null) {
                remoteVideoTexture.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
            }
            if (remoteVideo != null) {
                remoteVideo.setImageDrawable(null);
                if (!enabled) remoteVideo.setBackgroundColor(Color.BLACK);
                else remoteVideo.setBackgroundColor(Color.TRANSPARENT);
            }
            if (videoStateOverlay != null) {
                videoStateOverlay.setText(enabled ? "WAITING FOR VIDEO" : "VIDEO OFF");
                videoStateOverlay.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override public void onVideoFrameRendered() {
        lastRemoteVideoUiFrameElapsedMs = android.os.SystemClock.elapsedRealtime();
        scheduleRemoteVideoUiStallCheck();
        runOnUiThread(() -> {
            if (remoteVideo != null) remoteVideo.setImageDrawable(null);
            if (videoStateOverlay != null && remoteVideoOn) {
                SessionBus.DiagnosticsSnapshot d = SessionBus.diagnosticsSnapshot();
                videoStateOverlay.setText(d.codec != null && d.codec.startsWith("H.264")
                        ? "VIDEO ON • " + d.codec
                        : "VIDEO ON");
                videoStateOverlay.setVisibility(View.VISIBLE);
            }
            updateH264PictureInPictureAspect();
        });
    }

    @Override public void onRemoteVideoRotation(int degrees) {
        QuietLog.log("UI", "remote_rotation", "degrees=" + degrees);
        runOnUiThread(() -> {
            applyVideoTextureTransform(remoteVideoTexture, degrees, false, false);
            updateH264PictureInPictureAspect();
        });
    }

    @Override public void onLocalVideoRotation(int degrees) {
        QuietLog.log("UI", "local_rotation", "degrees=" + degrees);
        runOnUiThread(() -> {
            updateLocalPreviewLayout();
            applyVideoTextureTransform(localVideoTexture, degrees, true, false);
        });
    }

    @Override public void onRemoteVideo(android.graphics.Bitmap b) {
        runOnUiThread(() -> {
            if (remoteVideo != null) {
                remoteVideo.setImageDrawable(null);
                if (b != null) {
                    lastRemoteVideoUiFrameElapsedMs = android.os.SystemClock.elapsedRealtime();
                    scheduleRemoteVideoUiStallCheck();
                    remoteVideo.setImageBitmap(b);
                    updatePictureInPictureAspect(b);
                    updateVideoStateFromFrame(b);
                } else if (pipPresentation && SessionBus.latestLocalVideo != null) {
                    remoteVideo.setImageBitmap(SessionBus.latestLocalVideo);
                } else if (videoStateOverlay != null) {
                    videoStateOverlay.setText(remoteVideoOn ? "NO VIDEO SIGNAL" : "VIDEO OFF");
                    videoStateOverlay.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void updateVideoStateFromFrame(android.graphics.Bitmap bitmap) {
        if (videoStateOverlay == null || bitmap == null || !remoteVideoOn) return;
        long now = System.currentTimeMillis();
        if (now - lastVideoStateCheckMs < 1000) return;
        lastVideoStateCheckMs = now;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width < 1 || height < 1) return;

        long luminance = 0;
        int samples = 0;
        for (int y = 1; y <= 5; y++) {
            int py = Math.min(height - 1, y * height / 6);
            for (int x = 1; x <= 5; x++) {
                int px = Math.min(width - 1, x * width / 6);
                int color = bitmap.getPixel(px, py);
                int r = Color.red(color);
                int g = Color.green(color);
                int b = Color.blue(color);
                luminance += (299L * r + 587L * g + 114L * b) / 1000L;
                samples++;
            }
        }

        int average = samples == 0 ? 255 : (int)(luminance / samples);
        if (average < 28) {
            videoStateOverlay.setText("VIDEO ON • LOW LIGHT");
            videoStateOverlay.setVisibility(View.VISIBLE);
        } else {
            videoStateOverlay.setText("VIDEO ON");
            videoStateOverlay.setVisibility(View.VISIBLE);
        }
    }

    private void scheduleRemoteVideoUiStallCheck() {
        final long generation = ++remoteVideoUiCheckGeneration;
        View anchor = videoStateOverlay != null ? videoStateOverlay : root;
        if (anchor == null) return;
        anchor.postDelayed(() -> {
            if (generation != remoteVideoUiCheckGeneration
                    || !renderedSession || !remoteVideoOn) return;
            long age = android.os.SystemClock.elapsedRealtime()
                    - lastRemoteVideoUiFrameElapsedMs;
            if (age < 3800L) return;
            if (videoStateOverlay != null) {
                videoStateOverlay.setText("VIDEO STALLED • RECOVERING");
                videoStateOverlay.setVisibility(View.VISIBLE);
            }
            QuietLog.log("UI", "remote_video_stalled",
                    "age_ms=" + age);
        }, 4000L);
    }

    @Override public void onLocalVideo(android.graphics.Bitmap b) {
        runOnUiThread(() -> {
            if (pipPresentation) {
                if (remoteVideo != null && SessionBus.latestVideo == null) {
                    remoteVideo.setImageDrawable(null);
                    if (b != null) remoteVideo.setImageBitmap(b);
                }
                return;
            }
            if (localPreview != null) {
                localPreview.setImageDrawable(null);
                if (b != null) {
                    localPreview.setImageBitmap(b);
                    if (SessionBus.latestVideo == null) updatePictureInPictureAspect(b);
                    localPreview.setVisibility(View.VISIBLE);
                } else {
                    localPreview.setVisibility(View.GONE);
                }
            }
        });
    }

    @Override public void onChatMessagesChanged(List<SessionBus.ChatMessage> messages) {
        runOnUiThread(() -> {
            renderChatMessages(messages);
            if (!devDummySession && chatDialog != null && chatDialog.isShowing()) {
                markChatReadAndClearNotification();
            } else {
                updateChatButtonBadge();
            }
        });
    }

    @Override public void onNearbyDevicesChanged(List<PeerDiscovery.Peer> peers) {
        runOnUiThread(() -> {
            if (!renderedSession && (selectedJoinTab == TAB_NEARBY || selectedJoinTab == TAB_KNOWN)) {
                renderJoinTabContent();
            }
        });
    }

    @Override public void onIncomingRequest(SessionBus.IncomingRequest request) {
        runOnUiThread(() -> {
            if (request == null) {
                if (incomingDialog != null) {
                    incomingDialog.dismiss();
                    incomingDialog = null;
                }
                return;
            }

            if (incomingDialog != null && incomingDialog.isShowing()) incomingDialog.dismiss();

            String message = incomingRequestMessage(request);

            incomingDialog = new android.app.AlertDialog.Builder(this)
                    .setTitle(request.name + " wants to connect")
                    .setMessage(message)
                    .setCancelable(false)
                    .setNegativeButton("Decline", (dialog, which) ->
                            startService(new Intent(this, SessionService.class)
                                    .setAction(SessionService.ACTION_INCOMING_DECLINE)))
                    .setPositiveButton("Accept", (dialog, which) ->
                            startService(new Intent(this, SessionService.class)
                                    .setAction(SessionService.ACTION_INCOMING_ACCEPT)))
                    .create();
            incomingDialog.show();
            refreshIncomingRequestCountdown(request);
        });
    }

    private String incomingRequestMessage(SessionBus.IncomingRequest request) {
        long remainingMs = Math.max(0L, request.expiresAtMs - System.currentTimeMillis());
        long seconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        return (request.known ? "Known device" : "New device")
                + "\n\nVerification key:\n" + request.verification
                + "\n\nCheck that the same key is shown on the other phone."
                + "\n\nExpires in " + seconds + "s";
    }

    private void refreshIncomingRequestCountdown(SessionBus.IncomingRequest request) {
        if (request == null || incomingDialog == null || !incomingDialog.isShowing()
                || SessionBus.incomingRequest != request) return;
        incomingDialog.setMessage(incomingRequestMessage(request));
        long remaining = request.expiresAtMs - System.currentTimeMillis();
        if (remaining <= 0L) return;
        incomingDialog.getWindow().getDecorView().postDelayed(
                () -> refreshIncomingRequestCountdown(request), Math.min(1000L, remaining));
    }

    @Override public void onOutgoingRequest(SessionBus.OutgoingRequest request) {
        runOnUiThread(() -> {
            if (request == null) {
                if (outgoingDialog != null) {
                    outgoingDialog.dismiss();
                    outgoingDialog = null;
                }
                return;
            }

            if (outgoingDialog != null && outgoingDialog.isShowing()) outgoingDialog.dismiss();

            String message = outgoingRequestMessage(request);

            outgoingDialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("Calling " + request.name)
                    .setMessage(message)
                    .setCancelable(false)
                    .setNegativeButton("Cancel", (dialog, which) ->
                            startService(new Intent(this, SessionService.class)
                                    .setAction(SessionService.ACTION_OUTGOING_CANCEL)))
                    .create();
            outgoingDialog.show();
            refreshOutgoingRequestCountdown(request);
        });
    }

    private String outgoingRequestMessage(SessionBus.OutgoingRequest request) {
        long remainingMs = Math.max(0L, request.expiresAtMs - System.currentTimeMillis());
        long seconds = Math.max(0L, (remainingMs + 999L) / 1000L);
        return (request.known ? "Known device" : "New device")
                + "\n\nVerification key:\n" + request.verification
                + "\n\nCheck that the same key is shown on the receiving phone."
                + "\n\nWaiting for them to accept…"
                + "\nExpires in " + seconds + "s";
    }

    private void refreshOutgoingRequestCountdown(SessionBus.OutgoingRequest request) {
        if (request == null || outgoingDialog == null || !outgoingDialog.isShowing()
                || SessionBus.outgoingRequest != request) return;
        outgoingDialog.setMessage(outgoingRequestMessage(request));
        long remaining = request.expiresAtMs - System.currentTimeMillis();
        if (remaining <= 0L) return;
        outgoingDialog.getWindow().getDecorView().postDelayed(
                () -> refreshOutgoingRequestCountdown(request), Math.min(1000L, remaining));
    }

    @Override public void onKnownDevicesChanged() {
        runOnUiThread(() -> {
            if (!renderedSession && selectedJoinTab == TAB_KNOWN) renderJoinTabContent();
        });
    }

    @Override public void onPairingModeChanged(boolean enabled) {
        runOnUiThread(() -> {
            if (!renderedSession && (selectedJoinTab == TAB_NEARBY || selectedJoinTab == TAB_KNOWN)) {
                renderJoinTabContent();
            }
        });
    }

    @Override public void onDisconnected(String reason) {
        runOnUiThread(() -> {
            if (incomingDialog != null) { incomingDialog.dismiss(); incomingDialog = null; }
            if (outgoingDialog != null) { outgoingDialog.dismiss(); outgoingDialog = null; }
            if (chatDialog != null) { chatDialog.dismiss(); chatDialog = null; }
            videoFullscreenActive = false;
            if (reason != null && reason.endsWith(" disconnected")
                    && !"Disconnected".equals(reason)) {
                lastDisconnectBanner = reason;
            } else {
                lastDisconnectBanner = null;
            }
            if (!isFinishing()) showJoinLobby();
        });
    }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private LinearLayout panel() { LinearLayout l = column(); l.setPadding(dp(16),dp(14),dp(16),dp(14)); l.setBackground(makeRound(panelColor(),16)); return l; }
    private TextView text(String s, int sp, int color, boolean bold) { TextView t = new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t; }
    private Button iconButton(String icon, String description) {
        Button b = button(icon, panel2(), Color.WHITE);
        b.setTextSize(20);
        b.setPadding(0,0,0,0);
        b.setContentDescription(description);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private void applySleepingToggleStyle(Button button, boolean enabled,
                                          String onText, String offText) {
        button.setText(enabled ? onText : offText);
        button.setTextSize(11);
        button.setTextColor(enabled ? Color.rgb(8,20,35) : Color.rgb(255,190,190));
        button.setBackground(makeRound(enabled ? accent() : Color.rgb(78,26,29),14));
        button.setContentDescription(enabled ? onText : offText);
    }

    private void applyRemoteBabyStateStyle(Button button, boolean enabled, boolean known,
                                           String onText, String offText, String unknownText) {
        button.setText(known ? (enabled ? onText : offText) : unknownText);
        button.setEnabled(known);
        button.setAlpha(known ? 1f : 0.72f);
        button.setTextSize(known ? 10 : 11);
        button.setTextColor(known
                ? (enabled ? Color.rgb(8,20,35) : Color.rgb(255,190,190))
                : Color.LTGRAY);
        button.setBackground(makeRound(known
                ? (enabled ? accent() : Color.rgb(78,26,29))
                : panel2(), 14));
        button.setContentDescription(known
                ? (enabled ? onText : offText)
                : "Waiting for Baby Station state");
    }

    private void applyTalkButtonState(Button button, boolean talking, boolean compact) {
        if (talking) {
            button.setText(compact ? "● TALKING" : "●  TALKING…");
            button.setTextSize(compact ? 10 : 14);
            button.setTextColor(Color.rgb(5,35,18));
            button.setBackground(makeRound(Color.rgb(54,211,118),14));
            button.setContentDescription("Talking to Baby Station");
        } else {
            button.setText(compact ? "🎙" : "HOLD TO TALK");
            button.setTextSize(compact ? 20 : 14);
            button.setTextColor(Color.rgb(8,20,35));
            button.setBackground(makeRound(accent(),14));
            button.setContentDescription("Hold to talk");
        }
    }

    private Button iconPrimary(String icon, String description) {
        Button b = button(icon, accent(), Color.rgb(8,20,35));
        b.setTextSize(20);
        b.setPadding(0,0,0,0);
        b.setContentDescription(description);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private Button iconDanger(String icon, String description) {
        Button b = button(icon, Color.rgb(78,26,29), Color.rgb(255,190,190));
        b.setTextSize(20);
        b.setPadding(0,0,0,0);
        b.setContentDescription(description);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        return b;
    }

    private LinearLayout.LayoutParams iconWeightLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-1,1f);
        p.setMargins(dp(2),0,dp(2),0);
        return p;
    }

    private Button primary(String s) { return button(s, accent(), Color.rgb(8,20,35)); }
    private Button secondary(String s) { return button(s, panel2(), Color.WHITE); }
    private Button danger(String s) { return button(s, Color.rgb(78,26,29), Color.rgb(255,160,160)); }

    private Button compactPrimary(String s) {
        Button b = primary(s);
        styleCompactBabyButton(b);
        return b;
    }

    private Button compactSecondary(String s) {
        Button b = secondary(s);
        styleCompactBabyButton(b);
        return b;
    }

    private Button compactDanger(String s) {
        Button b = danger(s);
        styleCompactBabyButton(b);
        return b;
    }

    private void styleCompactBabyButton(Button b) {
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(12),dp(7),dp(12),dp(7));
    }

    private LinearLayout.LayoutParams compactButtonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2,-2);
        p.setMargins(dp(3),dp(2),dp(3),dp(2));
        return p;
    }
    private Button button(String s, int bg, int fg) { Button b = new Button(this); b.setText(s); b.setTextColor(fg); b.setTextSize(14); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setAllCaps(false); b.setBackground(makeRound(bg,14)); return b; }
    private android.graphics.drawable.GradientDrawable makeRound(int color, int radius) { android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(radius)); return g; }
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w,h); p.setMargins(dp(l),dp(t),dp(r),dp(b)); return p; }
    private LinearLayout.LayoutParams weightLp() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,-1,1f); p.setMargins(dp(4),0,dp(4),0); return p; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private int bg(){ return Color.rgb(13,17,23); }
    private int panelColor(){ return Color.rgb(22,27,34); }
    private int panel2(){ return Color.rgb(33,38,45); }
    private int muted(){ return Color.rgb(139,148,158); }
    private int accent(){ return Color.rgb(88,166,255); }
}
