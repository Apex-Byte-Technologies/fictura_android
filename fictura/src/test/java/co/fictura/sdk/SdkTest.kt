package co.fictura.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A fake server: records requests, answers by path (default 200 `{}`). */
class FakeTransport(val answer: (Request) -> Response = { Response(200, emptyMap(), "{}") }) : Transport {
    val requests = java.util.Collections.synchronizedList(ArrayList<Request>())
    override fun send(request: Request, done: (Response) -> Unit) {
        requests.add(request)
        Thread { done(answer(request)) }.start()
    }
    fun to(path: String) = requests.filter { it.url.substringBefore('?').endsWith(path) }
}

fun body(r: Request) = Json.parse(r.body)!!.obj!!

fun vectors(name: String): JsonMap = Json.parse(SdkTest::class.java.classLoader!!.getResource("vectors/$name.json")!!.readText())!!.obj!!

fun core(t: Transport, store: KeyValueStore = MemoryStore()) =
    Core(Api("https://api.test", "gk_test"), store, t, "and-test1234", mapOf("\$lib" to Json.Str(SdkInfo.LIB)), random = { 0.5 }, flushIntervalMs = 3_600_000)

fun Core.flushAndWait() {
    val l = CountDownLatch(1)
    flush { l.countDown() }
    assertTrue("flush finished", l.await(5, TimeUnit.SECONDS))
}

class SdkTest {
    @Test fun jsonRoundTrip() {
        val text = """{"a":"x\n\"y\"","b":3,"c":2.5,"d":[true,null],"e":{"f":-1}}"""
        val j = Json.parse(text)!!
        assertEquals(text, Json.stringify(j))
        assertNull(Json.parse("{bad"))
    }

    @Test fun samplingAndSeedsMatchTheAppSdk() {
        for (c in vectors("sampling")["cases"]!!.arr!!) {
            val dev = c["input"]!!["device_id"]?.string
            val ev = c["input"]!!["event_name"]!!.string!!
            c["expected"]!!["bucket"]?.double?.let { assertEquals(it, Sampling.bucket(dev!!, ev), 1e-12) }
            c["expected"]!!["keep"]?.obj?.forEach { (rate, keep) -> assertEquals(keep.bool, Sampling.keep(dev, ev, rate.toDouble())) }
        }
        for (c in vectors("user_seed")["cases"]!!.arr!!) {
            assertEquals(c["expected"]!!["seed"]!!.int, PlacementEngine.seedForUser(c["input"]!!["user_id"]!!.string!!))
        }
    }

    @Test fun rulesMatchTheAppSdk() {
        val v = vectors("rules")
        val ctx = v["context"]!!.obj!!
        val occ = ctx["occurrences"]?.obj ?: emptyMap()
        val server = ctx["server"]?.obj
        val rc = RuleContext(ctx["user"]?.obj ?: emptyMap(), ctx["seed"]!!.int!!, ctx["isLoggedIn"]?.bool == true, ctx["params"]?.obj ?: emptyMap(),
            ctx["eventName"]?.string ?: "", ctx["device"]?.obj ?: emptyMap(), server?.get("country")?.string, server?.get("billing_state")?.string,
            server?.get("attribution")?.obj, ctx["now"]!!.double!!, { p -> occ[p]?.arr?.mapNotNull { it.double } ?: emptyList() }, ctx["installedAt"]?.double)
        for (c in v["cases"]!!.arr!!) {
            val name = c["name"]?.string
            if (c["kind"]?.string == "compareVersions") assertEquals(name, c["expected"]?.int, PlacementRules.compareVersions(c["input"]!!["a"]!!, c["input"]!!["b"]!!))
            else assertEquals(name, c["expected"]!!.bool, PlacementRules.evaluate(c["input"]!!["rule"], rc))
        }
    }

    @Test fun placementDecisionsMatchTheAppSdk() {
        val cases = vectors("placement_engine")["cases"]!!.arr!!
        assertTrue(cases.size > 10)
        for (c in cases) {
            val i = c["input"]!!.obj!!
            val ctx = i["context"]?.obj ?: emptyMap()
            val server = ctx["server"]?.obj
            val occ = i["occurrences"]?.obj?.mapValues { (_, v) -> v.arr!!.mapNotNull { it.double } } ?: emptyMap()
            val rc = RuleContext(ctx["user"]?.obj ?: emptyMap(), ctx["seed"]?.int ?: 0, ctx["isLoggedIn"]?.bool == true, emptyMap(), "",
                ctx["device"]?.obj ?: emptyMap(), server?.get("country")?.string, server?.get("billing_state")?.string, server?.get("attribution")?.obj,
                0.0, { emptyList() }, ctx["installedAt"]?.double)
            val stored = i["assignments"]?.obj?.mapValues { (_, a) -> PlacementEngine.Stored(a["variant_id"]!!.string!!, a["experiment_id"]?.string, a["placement"]?.string, a["confirmed"]?.bool) } ?: emptyMap()
            val rnd = i["random"]?.double ?: 0.5
            val r = PlacementEngine.resolve(PlacementEngine.Input(i["config"]!!.obj!!, i["placement"]!!.string!!, i["params"]?.obj ?: emptyMap(),
                i["now"]!!.double!!, i["subscribed"]?.bool == true, rc, occ, stored) { rnd })
            val e = c["expected"]!!.obj!!
            val name = c["name"]!!.string
            assertEquals(name, e["type"]!!.string, r.type)
            assertEquals(name, e["source"]?.string, r.source)
            assertEquals(name, e["campaign_id"]?.string, r.campaignId)
            assertEquals(name, e["audience_id"]?.string, r.audienceId)
            assertEquals(name, e["experiment_id"]?.string, r.experimentId)
            assertEquals(name, e["variant_id"]?.string, r.variantId)
            assertEquals(name, e["paywall_id"]?.string, r.paywallId)
            assertEquals(name, e["skipReason"]?.string, r.skipReason)
            assertEquals(name, e["unmatched"]?.obj?.mapValues { it.value.string } ?: emptyMap<String, String?>(), r.unmatched)
            assertEquals(name, e["assignment"]?.get("from")?.string, r.assignment?.from)
            assertEquals(name, e["paywall_present"]?.bool == true, r.paywall != null)
        }
    }

    @Test fun batchFormatHeadersAndIdentity() {
        val t = FakeTransport()
        val c = core(t)
        c.track("before")
        c.identify("user_77")
        c.setRevenueCatId("\$RCAnonymousID:abc")
        c.setAttributes(mapOf("plan" to Json.Str("pro"), "\$bad" to Json.of(1)))
        c.track("after", properties = mapOf("style" to Json.Str("modern")))
        c.flushAndWait()
        val reqs = t.to("/api/v1/events/log")
        assertEquals(2, reqs.size)
        assertNull(reqs[0].headers["X-Account-ID"])
        assertNull(body(reqs[0])["attributes"])
        val r = reqs[1]
        assertEquals("android/${SdkInfo.VERSION}", r.headers["x-fictura-sdk"])
        assertEquals("gk_test", r.headers["x-api-key"])
        assertEquals("user_77", r.headers["X-Account-ID"])
        val b = body(r)
        assertEquals("android", b["platform"]!!.string)
        assertEquals("\$RCAnonymousID:abc", b["revenuecat_id"]!!.string)
        assertEquals(mapOf("plan" to Json.Str("pro")), b["attributes"]!!.obj)
        val e = b["events"]!!.arr!![0].obj!!
        assertEquals(setOf("event_name", "status", "properties", "uuid", "timestamp"), e.keys)
        val p = e["properties"]!!.obj!!
        assertEquals("fictura-android", p["\$lib"]!!.string)
        assertEquals(2, p["\$seq"]!!.int)
        assertEquals('7', p["\$session_id"]!!.string!![14])
        assertEquals(0, c.pendingCount)
    }

    @Test fun retriesOn5xxAndDropsOn4xx() {
        var status = 503
        val t = FakeTransport { Response(status, mapOf("Retry-After" to "60"), "{}") }
        val c = core(t)
        c.track("a")
        c.flushAndWait()
        assertEquals(1, c.pendingCount)
        status = 400
        val d = core(t)
        d.track("bad")
        d.flushAndWait()
        assertEquals(0, d.pendingCount)
        status = 200
        d.track("next")
        d.flushAndWait()
        val last = body(t.to("/api/v1/events/log").last())
        assertEquals(1, last["client_dropped"]!!.int)
    }

    @Test fun killSwitchSamplingOptOutAndRestart() {
        val store = MemoryStore()
        val c = core(FakeTransport { Response(0, emptyMap(), null) }, store)
        c.applyEventSettings(setOf("off"), mapOf("never" to 0.0))
        c.track("off"); c.track("never"); c.track("kept")
        assertEquals(1, c.pendingCount)
        val again = core(FakeTransport(), store)
        assertEquals(1, again.pendingCount)
        again.setOptOut(true)
        again.track("blocked")
        assertEquals(0, again.pendingCount)
        assertTrue(again.isOptedOut)
    }

    @Test fun readsFromTheCoreThreadDoNotDeadlock() {
        val c = core(FakeTransport())
        val ids = c.work.submit<EventIds> { c.ids() }.get(2, TimeUnit.SECONDS)
        assertEquals("and-test1234", ids.deviceId)
    }

    @Test fun deliveryHelpers() {
        assertEquals(500L, Delivery.backoffMs(1, 0.5))
        assertEquals(120_000L, Delivery.retryAfterMs("120", 0))
        assertNull(Delivery.retryAfterMs("-5", 0))
        assertEquals(1023, Delivery.truncate("a".repeat(1023) + "😀").length)
        assertNull(legalId("guest"))
        assertEquals("u9", legalId(" u9 "))
    }

    @Test fun attributionKeepsOnlyUuidClicks() {
        val click = "6f1c2a9e-1b2c-4d3e-8f90-123456789abc"
        val p = Fictura.attributionParams("shop://open?growth_click=$click&utm_source=tiktok&gclid=g")!!
        assertEquals(click, p["growth_click_id"]!!.string)
        assertEquals("g", p["click_id"]!!.string)
        assertNull(Fictura.attributionParams("shop://open?growth_click=c_1&utm_source=x")!!["growth_click_id"])
        assertNull(Fictura.attributionParams("shop://open"))
    }
}

class PaywallBridgeTest {
    private class Host : PaywallHost {
        val scripts = ArrayList<String>()
        val closed = ArrayList<Pair<String, String>>()
        val opened = ArrayList<String>()
        var purchaseResult: PurchaseResult = PurchaseResult.Purchased("t1", "trial")
        override fun evaluate(js: String) { scripts.add(js) }
        override fun close(result: String, productId: String?, reason: String) { closed.add(result to reason) }
        override fun openUrl(url: String, inApp: Boolean) { opened.add(url) }
        override fun haptic(kind: String) {}
        override fun alert(message: String) {}
        override fun purchase(pkg: FicturaPackage, context: PurchaseContext, done: (PurchaseResult) -> Unit) = done(purchaseResult)
    }

    private object Store : PurchaseController {
        override fun packages(productIds: List<String>, done: (List<FicturaPackage>) -> Unit) =
            done(productIds.map { FicturaPackage(it, it, "$9.99", 9_990_000, "USD", "monthly", trialDays = 7, trialEligible = true) })
        override fun purchase(activity: android.app.Activity, pkg: FicturaPackage, context: PurchaseContext, done: (PurchaseResult) -> Unit) = done(PurchaseResult.Cancelled)
    }

    private fun bridge(c: Core, checkout: String? = null, closes: MutableList<String> = ArrayList()): PaywallBridge {
        val info = PaywallInfo("pw_a", "upgrade", "c1", "a1", "e1", "v1", "non_gated", "fullscreen")
        val req = PresentRequest(1, info, "<html><head></head><body>Hi {{params.name}}</body></html>", 3, emptyMap(), mapOf("name" to Json.Str("<Ann>")),
            {}, { r, _, _ -> closes.add(r) }, {}, { _, _ -> })
        return PaywallBridge(c, Store, req, mapOf("primary" to "pro_monthly"), mapOf("copy" to Json.obj("title" to "Go")), checkout, {})
    }

    @Test fun scriptParamsAndEvents() {
        val t = FakeTransport()
        val c = core(t)
        val b = bridge(c)
        val h = Host()
        b.host = h
        assertTrue(b.html.contains("Hi &lt;Ann&gt;"))
        b.loadPackages {}
        val js = b.injectedScript("/*binder*/")
        assertTrue(js.contains("window.__FICTURA__ = {"))
        assertTrue(js.contains("\"primary\":{\"id\":\"pro_monthly\""))
        assertTrue(js.contains("FicturaAndroid.postMessage"))
        b.handle("""{"type":"ready"}""")
        b.handle("""{"type":"track","event":"paywall_plan_selected","properties":{"plan":"annual"}}""")
        b.handle("""{"type":"track","event":"faq_opened"}""")
        b.handle("""{"type":"purchase","package_id":"pro_monthly"}""")
        b.handle("not json")
        c.flushAndWait()
        val names = t.to("/api/v1/events/log").flatMap { body(it)["events"]!!.arr!! }.map { it["event_name"]!!.string }
        assertEquals(listOf("paywall_shown", "paywall_product_selected", "paywall_web_faq_opened", "paywall_cta_tapped", "purchase_started", "purchase_completed", "paywall_dismissed"), names)
        assertEquals("purchased" to "manual", h.closed.first())
        assertTrue(h.scripts.any { it.contains("fictura:purchase_result") && it.contains("purchased") })
    }

    @Test fun webCheckoutLinksAndClose() {
        val c = core(FakeTransport())
        val b = bridge(c, checkout = "https://api.fictura.co/c/acme?uid=and-1")
        val h = Host()
        b.host = h
        b.handle("""{"type":"web_checkout"}""")
        b.handle("""{"type":"open_url","url":"javascript:alert(1)"}""")
        b.handle("""{"type":"open_url","url":"https://example.com/terms"}""")
        b.handle("""{"type":"close"}""")
        assertEquals(listOf("https://api.fictura.co/c/acme?uid=and-1", "https://example.com/terms"), h.opened)
        assertEquals("declined" to "manual", h.closed.last())
        assertFalse(PaywallBridge.linkOk("data:text/html,x"))
        assertNotNull(PaywallBridge.customAnswer(mapOf("ok" to true, "values" to mapOf("name" to "Ann", "Bad Key" to 1)))["values"]!!["name"])
    }
}
