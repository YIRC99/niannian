package dev.niannian.reminder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit alarm receiver plus system boot/time/permission restoration. No background UI launch. */
public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if ("dev.niannian.reminder.FIRE".equals(intent.getAction())) {
            String scope = intent.getStringExtra("scope"), id = intent.getStringExtra("plan_id");
            if (scope != null && id != null) ReminderCoordinator.fire(context, scope, id);
        } else {
            PendingResult pending = goAsync();
            ReminderCoordinator.restore(context, pending::finish);
        }
    }
}
