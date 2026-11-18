package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.owpengram.OwpengramServer;
import org.telegram.owpengram.OwpengramServers;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;

public class ServerSelectFragment extends BaseFragment {

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private final int loginAccount;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable connectPoll;
    private boolean destroyed;
    private boolean autoJoinStarted;

    public ServerSelectFragment() {
        this.loginAccount = -1;
    }

    public ServerSelectFragment(int loginAccount) {
        this.loginAccount = loginAccount;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle("ViewGram");
        actionBar.setSubtitle("Connecting...");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = frame;

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(24), dp(24), dp(24));

        RadialProgressView progressView = new RadialProgressView(context);
        progressView.setSize(dp(36));
        box.addView(progressView, LayoutHelper.createLinear(44, 44, Gravity.CENTER_HORIZONTAL));

        TextView label = new TextView(context);
        label.setText("Connecting to ViewGram...");
        label.setTextSize(15);
        label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        label.setGravity(Gravity.CENTER);
        box.addView(label, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 14, 0, 0));

        frame.addView(box, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        if (!autoJoinStarted) {
            autoJoinStarted = true;
            uiHandler.post(() -> {
                if (!destroyed) {
                    proceedJoin(OwpengramServers.owpengramServer());
                }
            });
        }

        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (connectPoll != null) uiHandler.removeCallbacks(connectPoll);
        super.onFragmentDestroy();
    }

    private void proceedJoin(OwpengramServer server) {
        if (destroyed || getParentActivity() == null || server == null) return;
        final int targetAccount = (loginAccount >= 0) ? loginAccount : currentAccount;

        OwpengramServers.setServerForAccount(server.id, targetAccount);
        OwpengramServers.applyServerToAccount(server, targetAccount);
        ConnectionsManager.getInstance(targetAccount).resumeNetworkMaybe();

        waitForConnection(targetAccount, server);
    }

    private void waitForConnection(int account, OwpengramServer server) {
        final long start = SystemClock.elapsedRealtime();
        connectPoll = new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                int state = ConnectionsManager.native_getConnectionState(account);
                if (state == ConnectionsManager.ConnectionStateConnected) {
                    checkEmailSignupAndGoToLogin(account, server);
                } else if (SystemClock.elapsedRealtime() - start > CONNECT_TIMEOUT_MS) {
                    showConnectFailed(server);
                } else {
                    uiHandler.postDelayed(this, 200);
                }
            }
        };
        uiHandler.postDelayed(connectPoll, 200);
    }

    private void checkEmailSignupAndGoToLogin(int account, OwpengramServer server) {
        TLRPC.TL_help_getAppConfig req = new TLRPC.TL_help_getAppConfig();
        req.hash = 0;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            boolean emailSignupEnabled = false;
            if (response instanceof TLRPC.TL_help_appConfig) {
                TLRPC.JSONValue config = ((TLRPC.TL_help_appConfig) response).config;
                if (config instanceof TLRPC.TL_jsonObject) {
                    for (TLRPC.TL_jsonObjectValue entry : ((TLRPC.TL_jsonObject) config).value) {
                        if ("email_signup_enabled".equals(entry.key) && entry.value instanceof TLRPC.TL_jsonBool) {
                            emailSignupEnabled = ((TLRPC.TL_jsonBool) entry.value).value;
                            break;
                        }
                    }
                }
            }
            if (destroyed) return;
            goToLogin(emailSignupEnabled);
        }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void showConnectFailed(OwpengramServer server) {
        if (destroyed || getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Connection failed");
        b.setMessage("Cannot reach ViewGram (" + server.host + ":" + server.port + ").");
        b.setPositiveButton("Retry", (d, w) -> proceedJoin(server));
        b.setNegativeButton("Continue", (d, w) -> goToLogin(false));
        showDialog(b.create());
    }

    private void goToLogin(boolean emailSignupEnabled) {
        if (destroyed) return;
        LoginActivity login = (loginAccount >= 0 && loginAccount != UserConfig.selectedAccount)
                ? new LoginActivity(loginAccount)
                : new LoginActivity();
        login.setEmailSignupEnabled(emailSignupEnabled);
        presentFragment(login, true);
    }
}
