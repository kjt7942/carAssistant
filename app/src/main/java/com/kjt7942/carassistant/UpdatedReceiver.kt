package com.kjt7942.carassistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 앱이 업데이트로 교체되면 새 버전을 바로 실행한다. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } catch (_: Exception) {
        }
    }
}
