package is.quietlink.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.widget.Toast;

public final class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_STATUS =
            "is.quietlink.app.UPDATE_INSTALL_STATUS";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm;
            if (Build.VERSION.SDK_INT >= 33) {
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
            } else {
                @SuppressWarnings("deprecation")
                Intent old = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                confirm = old;
            }
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
                QuietLog.log("UPDATE", "installer_confirmation", "");
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            QuietLog.log("UPDATE", "install_success", "");
            Toast.makeText(context, "QuietLink updated", Toast.LENGTH_LONG).show();
            return;
        }

        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        QuietLog.log("UPDATE", "install_result",
                "status=" + status + " message_present=" + (message == null ? 0 : 1));
        Toast.makeText(context,
                "QuietLink update was not installed",
                Toast.LENGTH_LONG).show();
    }
}
