package co.fictura.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an SDK that wraps this one (fictura_flutter) relies on. */
class WrapperTest {
    @Test fun wrapperHeaderIsCleaned() {
        assertEquals("flutter/0.1.0", FicturaWrapper("flutter", "0.1.0").header)
        assertEquals("flutter/0.1.0beta", FicturaWrapper("Flutter ", "0.1.0 beta").header)
        assertNull(FicturaWrapper("", "0.1.0").header)
        assertNull(FicturaWrapper("flutter", "  ").header)
    }

    @Test fun wrapperPropertiesSplitTheHeader() {
        assertEquals(mapOf("\$wrapper" to Json.Str("flutter"), "\$wrapper_version" to Json.Str("1.2.3+4")),
            FicturaWrapper("flutter", "1.2.3+4").properties())
        assertEquals(emptyMap<String, Json>(), FicturaWrapper("", "1").properties())
    }

    @Test fun wrappedBatchCarriesBothHeadersAndWrapperProperties() {
        val t = FakeTransport()
        val w = FicturaWrapper("flutter", "0.1.0")
        val c = Core(Api("https://api.test", "gk_test", w.header), MemoryStore(), t, "and-test1234",
            mapOf("\$lib" to Json.Str(SdkInfo.LIB)) + w.properties(), random = { 0.5 }, flushIntervalMs = 3_600_000)
        c.track("photo_saved")
        c.flushAndWait()
        val r = t.to("/api/v1/events/log").single()
        assertEquals("the native SDK keeps its own name", "android/${SdkInfo.VERSION}", r.headers["x-fictura-sdk"])
        assertEquals("flutter/0.1.0", r.headers["x-fictura-wrapper"])
        val p = body(r)["events"]!!.arr!![0].obj!!["properties"]!!.obj!!
        assertEquals("fictura-android", p["\$lib"]!!.string)
        assertEquals("flutter", p["\$wrapper"]!!.string)
        assertEquals("0.1.0", p["\$wrapper_version"]!!.string)
    }

    @Test fun unwrappedRequestsHaveNoWrapperHeader() {
        val t = FakeTransport()
        val c = core(t)
        c.track("photo_saved")
        c.flushAndWait()
        assertNull(t.to("/api/v1/events/log").single().headers["x-fictura-wrapper"])
    }

    @Test fun foreignErrorKeepsItsOwnStackAndRuntime() {
        val t = FakeTransport()
        val r = ErrorReporter(core(t), "1.0+1", "test", "flutter/0.1.0")
        val stack = "#0      CartPage.checkout (package:shop/cart.dart:42:7)\n#1      _rootRun (dart:async/zone.dart:1399:47)"
        r.captureForeign("StateError", "Bad state: cart is empty", stack, "error", false, "flutter_error", "dart", mapOf("library" to "widgets library"))
        val req = waitFor(t, "/api/v1/errors")
        val e = body(req)["events"]!!.arr!![0].obj!!
        assertEquals("StateError", e["exc_type"]!!.string)
        assertEquals("Bad state: cart is empty", e["message"]!!.string)
        assertEquals("the Dart stack is sent as given", stack, e["stack"]!!.string)
        assertEquals("android", e["platform"]!!.string)
        assertEquals("flutter_error", e["mechanism"]!!.string)
        assertEquals(false, e["handled"]!!.bool)
        val ctx = e["contexts"]!!.obj!!
        assertEquals("dart", ctx["runtime"]!!.obj!!["name"]!!.string)
        assertEquals("flutter/0.1.0", ctx["app"]!!.obj!!["wrapper"]!!.string)
        assertEquals("android/${SdkInfo.VERSION}", ctx["app"]!!.obj!!["sdk"]!!.string)
        assertEquals("widgets library", e["tags"]!!.obj!!["library"]!!.string)
    }

    @Test fun foreignErrorWithoutTypeIsStillSent() {
        val t = FakeTransport()
        val r = ErrorReporter(core(t), "1.0+1", "test")
        r.captureForeign("  ", null, null, "warning", true, "manual", "dart")
        val e = body(waitFor(t, "/api/v1/errors"))["events"]!!.arr!![0].obj!!
        assertEquals("Error", e["exc_type"]!!.string)
        assertNull(e["stack"])
        assertNull(e["contexts"]!!.obj!!["app"]!!.obj!!["wrapper"])
    }

    private fun waitFor(t: FakeTransport, path: String): Request {
        val until = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < until) {
            t.to(path).firstOrNull()?.let { return it }
            Thread.sleep(20)
        }
        assertTrue("no request to $path", false)
        throw IllegalStateException()
    }
}
