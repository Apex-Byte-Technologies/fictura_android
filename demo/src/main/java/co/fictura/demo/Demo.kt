package co.fictura.demo

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import co.fictura.sdk.Fictura
import co.fictura.sdk.FicturaOptions
import co.fictura.sdk.FicturaPackage
import co.fictura.sdk.PaywallInfo
import co.fictura.sdk.PlacementHandler
import co.fictura.sdk.PurchaseContext
import co.fictura.sdk.PurchaseController
import co.fictura.sdk.PurchaseResult

class DemoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Fictura.configure(this, BuildConfig.FICTURA_KEY, FicturaOptions().apply {
            apiBase = BuildConfig.FICTURA_API
            debug = true
            flushIntervalMs = 2000
        })
        Fictura.purchaseController = FakeStore
    }
}

/** Buys instantly, so a paywall can be tested on an emulator without Play products. */
object FakeStore : PurchaseController {
    var subscribed = false
    override fun packages(productIds: List<String>, done: (List<FicturaPackage>) -> Unit) =
        done(productIds.map { FicturaPackage(it, it, "$4.99", 4_990_000, "USD", "monthly", trialDays = 7, trialEligible = true) })
    override fun purchase(activity: Activity, pkg: FicturaPackage, context: PurchaseContext, done: (PurchaseResult) -> Unit) {
        subscribed = true
        done(PurchaseResult.Purchased("fake-${System.currentTimeMillis()}", "trial"))
    }
    override fun restore(done: (Boolean) -> Unit) = done(subscribed)
    override fun isSubscribed(done: (Boolean) -> Unit) = done(subscribed)
}

class MainActivity : Activity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 40) }
        fun button(label: String, tag: String, action: () -> Unit) = col.addView(Button(this).apply { text = label; contentDescription = tag; setOnClickListener { action() } })
        button("Track photo_saved", "track") { Fictura.track("photo_saved", mapOf("style" to "modern", "count" to 3)); note("tracked") }
        button("Identify android_tester_1", "identify") { Fictura.identify("android_tester_1", mapOf("plan" to "free")); note("identified") }
        button("Show pro_feature (campaign A/B)", "pro_feature") { show("pro_feature") }
        button("Show upgrade (live paywall)", "upgrade") { show("upgrade") }
        button("Capture handled error", "capture") {
            try { Integer.parseInt("not a number") } catch (e: NumberFormatException) { Fictura.capture(e); note("captured") }
        }
        button("Open settings", "settings") { startActivity(Intent(this, SettingsActivity::class.java)) }
        button("Flush now", "flush") { Fictura.flush { note("flushed") } }
        log = TextView(this)
        col.addView(log)
        setContentView(ScrollView(this).apply { addView(col) })
        intent?.data?.let { Fictura.handle(it) }
        note("device ${Fictura.deviceId}")
    }

    private fun note(s: String) = runOnUiThread { log.text = "$s\n${log.text}" }

    private fun show(placement: String) = Fictura.register(this, placement, mapOf("name" to "Android Tester"), object : PlacementHandler() {
        override fun onPresent(info: PaywallInfo) = note("presented ${info.paywallId.take(8)}")
        override fun onDismiss(info: PaywallInfo, result: String) = note("dismissed $result")
        override fun onSkip(reason: String) = note("skipped $reason")
        override fun onError(error: String) = note("error $error")
    }) { note("feature ran for $placement") }
}

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Settings"; setPadding(40, 120, 40, 40) })
    }
}
