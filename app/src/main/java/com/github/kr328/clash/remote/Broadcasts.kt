package com.github.kr328.clash.remote

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import java.util.*

class Broadcasts(private val context: Application) {
    interface Observer {
        fun onServiceRecreated()
        fun onStarted()
        fun onStopped(cause: String?)
        fun onProfileChanged()
        fun onProfileUpdateCompleted(uuid: UUID?)
        fun onProfileUpdateFailed(uuid: UUID?, reason: String?)
        fun onProfileLoaded()

        /**
         * Screening progress. Only the settings screen cares, so the default
         * does nothing rather than making every activity handle a round of
         * screening it never asked for.
         */
        fun onProbTestProgress(progress: ProbTestState) {}
        fun onProbTestFinished(published: Boolean, summary: String) {}
    }

    /**
     * A screening round as the UI process sees it. The values mirror the
     * [com.github.kr328.clash.service.probtest.ProbTestProgress] the worker
     * reports, flattened into extras.
     */
    data class ProbTestState(
        val stage: String,
        val done: Int,
        val total: Int,
        val round: Int,
        val rounds: Int,
        val passed: Int,
        val failed: Int,
    )

    var clashRunning: Boolean = false

    private var registered = false
    private val receivers = mutableListOf<Observer>()
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.`package` != context?.packageName)
                return

            when (intent?.action) {
                Intents.ACTION_SERVICE_RECREATED -> {
                    clashRunning = false

                    receivers.forEach {
                        it.onServiceRecreated()
                    }
                }
                Intents.ACTION_CLASH_STARTED -> {
                    clashRunning = true

                    receivers.forEach {
                        it.onStarted()
                    }
                }
                Intents.ACTION_CLASH_STOPPED -> {
                    clashRunning = false

                    receivers.forEach {
                        it.onStopped(intent.getStringExtra(Intents.EXTRA_STOP_REASON))
                    }
                }
                Intents.ACTION_PROFILE_CHANGED ->
                    receivers.forEach {
                        it.onProfileChanged()
                    }
                Intents.ACTION_PROFILE_UPDATE_COMPLETED ->
                    receivers.forEach {
                        it.onProfileUpdateCompleted(
                            UUID.fromString(intent.getStringExtra(Intents.EXTRA_UUID)))
                    }
                Intents.ACTION_PROFILE_UPDATE_FAILED ->
                    receivers.forEach {
                        it.onProfileUpdateFailed(
                            UUID.fromString(intent.getStringExtra(Intents.EXTRA_UUID)),
                            intent.getStringExtra(Intents.EXTRA_FAIL_REASON))
                    }
                Intents.ACTION_PROFILE_LOADED -> {
                    receivers.forEach {
                        it.onProfileLoaded()
                    }
                }
                Intents.ACTION_PROBTEST_PROGRESS ->
                    receivers.forEach {
                        it.onProbTestProgress(
                            ProbTestState(
                                stage = intent.getStringExtra(Intents.EXTRA_STAGE).orEmpty(),
                                done = intent.getIntExtra(Intents.EXTRA_DONE, 0),
                                total = intent.getIntExtra(Intents.EXTRA_TOTAL, 0),
                                round = intent.getIntExtra(Intents.EXTRA_ROUND, 0),
                                rounds = intent.getIntExtra(Intents.EXTRA_ROUNDS, 0),
                                passed = intent.getIntExtra(Intents.EXTRA_PASSED, 0),
                                failed = intent.getIntExtra(Intents.EXTRA_FAILED, 0),
                            )
                        )
                    }
                Intents.ACTION_PROBTEST_FINISHED -> {
                    val summary = intent.getStringExtra(Intents.EXTRA_SUMMARY)

                    receivers.forEach {
                        it.onProbTestFinished(
                            intent.getBooleanExtra(Intents.EXTRA_PUBLISHED, false),
                            summary.orEmpty(),
                        )
                    }
                }
            }
        }
    }

    fun addObserver(observer: Observer) {
        receivers.add(observer)
    }

    fun removeObserver(observer: Observer) {
        receivers.remove(observer)
    }

    fun register() {
        if (registered)
            return

        try {
            context.registerReceiver(broadcastReceiver, IntentFilter().apply {
                addAction(Intents.ACTION_SERVICE_RECREATED)
                addAction(Intents.ACTION_CLASH_STARTED)
                addAction(Intents.ACTION_CLASH_STOPPED)
                addAction(Intents.ACTION_PROFILE_CHANGED)
                addAction(Intents.ACTION_PROFILE_UPDATE_COMPLETED)
                addAction(Intents.ACTION_PROFILE_UPDATE_FAILED)
                addAction(Intents.ACTION_PROFILE_LOADED)
                addAction(Intents.ACTION_PROBTEST_PROGRESS)
                addAction(Intents.ACTION_PROBTEST_FINISHED)
            })

            clashRunning = StatusClient(context).currentProfile() != null
        } catch (e: Exception) {
            Log.w("Register global receiver: $e", e)
        }
    }

    fun unregister() {
        if (!registered)
            return

        try {
            context.unregisterReceiver(broadcastReceiver)

            clashRunning = false
        } catch (e: Exception) {
            Log.w("Unregister global receiver: $e", e)
        }
    }
}