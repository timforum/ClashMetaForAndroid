package com.github.kr328.clash.common.constants

import com.github.kr328.clash.common.util.packageName

object Intents {
    // Public
    val ACTION_PROVIDE_URL = "$packageName.action.PROVIDE_URL"
    val ACTION_START_CLASH = "$packageName.action.START_CLASH"
    val ACTION_STOP_CLASH = "$packageName.action.STOP_CLASH"
    val ACTION_TOGGLE_CLASH = "$packageName.action.TOGGLE_CLASH"

    const val EXTRA_NAME = "name"

    // Self
    val ACTION_SERVICE_RECREATED = "$packageName.intent.action.CLASH_RECREATED"
    val ACTION_CLASH_STARTED = "$packageName.intent.action.CLASH_STARTED"
    val ACTION_CLASH_STOPPED = "$packageName.intent.action.CLASH_STOPPED"
    val ACTION_CLASH_REQUEST_STOP = "$packageName.intent.action.CLASH_REQUEST_STOP"
    val ACTION_PROFILE_CHANGED = "$packageName.intent.action.PROFILE_CHANGED"
    val ACTION_PROFILE_UPDATE_COMPLETED = "$packageName.intent.action.PROFILE_UPDATE_COMPLETED"
    val ACTION_PROFILE_UPDATE_FAILED = "$packageName.intent.action.PROFILE_UPDATE_FAILED"
    val ACTION_PROFILE_REQUEST_UPDATE = "$packageName.intent.action.REQUEST_UPDATE"
    val ACTION_PROFILE_SCHEDULE_UPDATES = "$packageName.intent.action.SCHEDULE_UPDATES"
    val ACTION_PROFILE_LOADED = "$packageName.intent.action.PROFILE_LOADED"
    val ACTION_OVERRIDE_CHANGED = "$packageName.intent.action.OVERRIDE_CHANGED"
    val ACTION_PROBTEST_REQUEST = "$packageName.intent.action.PROBTEST_REQUEST"
    val ACTION_PROBTEST_SCHEDULE = "$packageName.intent.action.PROBTEST_SCHEDULE"
    val ACTION_PROBTEST_PROGRESS = "$packageName.intent.action.PROBTEST_PROGRESS"
    val ACTION_PROBTEST_FINISHED = "$packageName.intent.action.PROBTEST_FINISHED"
    val ACTION_SPEEDTEST_REQUEST = "$packageName.intent.action.SPEEDTEST_REQUEST"

    // Group whose speed screening produced a progress or a result.
    const val EXTRA_GROUP = "group"

    const val EXTRA_STOP_REASON = "stop_reason"
    const val EXTRA_UUID = "uuid"
    const val EXTRA_FAIL_REASON = "fail_reason"
    const val EXTRA_STAGE = "stage"
    const val EXTRA_DONE = "done"
    const val EXTRA_TOTAL = "total"
    const val EXTRA_ROUND = "round"
    const val EXTRA_ROUNDS = "rounds"
    const val EXTRA_PASSED = "passed"
    const val EXTRA_FAILED = "failed"
    const val EXTRA_PUBLISHED = "published"
    const val EXTRA_SUMMARY = "summary"
}