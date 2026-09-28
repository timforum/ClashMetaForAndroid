package com.github.kr328.clash.service.util

import android.content.Context
import android.content.Intent
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.constants.Permissions
import java.util.*

fun Context.sendBroadcastSelf(intent: Intent) {
    sendBroadcast(
        intent.setPackage(this.packageName),
        Permissions.RECEIVE_SELF_BROADCASTS
    )
}

fun Context.sendProfileChanged(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_CHANGED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileLoaded(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_LOADED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileUpdateCompleted(uuid: UUID) {
    val intent = Intent(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())

    sendBroadcastSelf(intent)
}

fun Context.sendProfileUpdateFailed(uuid: UUID, reason: String) {
    val intent = Intent(Intents.ACTION_PROFILE_UPDATE_FAILED)
        .putExtra(Intents.EXTRA_UUID, uuid.toString())
        .putExtra(Intents.EXTRA_FAIL_REASON, reason)

    sendBroadcastSelf(intent)
}

fun Context.sendOverrideChanged() {
    val intent = Intent(Intents.ACTION_OVERRIDE_CHANGED)

    sendBroadcastSelf(intent)
}

fun Context.sendServiceRecreated() {
    sendBroadcastSelf(Intent(Intents.ACTION_SERVICE_RECREATED))
}

fun Context.sendClashStarted() {
    sendBroadcastSelf(Intent(Intents.ACTION_CLASH_STARTED))
}

fun Context.sendClashStopped(reason: String?) {
    sendBroadcastSelf(
        Intent(Intents.ACTION_CLASH_STOPPED).putExtra(
            Intents.EXTRA_STOP_REASON,
            reason
        )
    )
}

fun Context.sendProbTestProgress(
    stage: String,
    done: Int,
    total: Int,
    round: Int,
    rounds: Int,
    passed: Int,
    failed: Int,
) {
    val intent = Intent(Intents.ACTION_PROBTEST_PROGRESS)
        .putExtra(Intents.EXTRA_STAGE, stage)
        .putExtra(Intents.EXTRA_DONE, done)
        .putExtra(Intents.EXTRA_TOTAL, total)
        .putExtra(Intents.EXTRA_ROUND, round)
        .putExtra(Intents.EXTRA_ROUNDS, rounds)
        .putExtra(Intents.EXTRA_PASSED, passed)
        .putExtra(Intents.EXTRA_FAILED, failed)

    sendBroadcastSelf(intent)
}

fun Context.sendProbTestFinished(published: Boolean, summary: String) {
    val intent = Intent(Intents.ACTION_PROBTEST_FINISHED)
        .putExtra(Intents.EXTRA_PUBLISHED, published)
        .putExtra(Intents.EXTRA_SUMMARY, summary)

    sendBroadcastSelf(intent)
}
