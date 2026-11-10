package com.clipisle.btplugin

import org.junit.Assert.*
import org.junit.Test

class PreviewRequestsTest {
    @Test fun `early cancellation prevents pending request without affecting another ID`() {
        val requests = PreviewRequests()
        assertTrue(requests.cancel("first"))
        assertTrue(requests.begin("first")!!.canceled.get())
        assertFalse(requests.begin("second")!!.canceled.get())
    }
    @Test fun `active cancellation survives attaching a later network call`() {
        val requests = PreviewRequests()
        val session = requests.begin("first")!!
        assertTrue(requests.cancel("first"))
        val call = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("https://whatslink.info/").build())
        session.attach(call)
        assertTrue(call.isCanceled())
        session.detach(call)
        requests.finish("first", session)
        assertFalse(requests.begin("fresh")!!.canceled.get())
    }
    @Test fun `bounded cancellation queue never silently evicts acknowledged requests`() {
        var time = 0L
        val requests = PreviewRequests({ time }, 1)
        assertTrue(requests.cancel("first"))
        assertFalse(requests.cancel("second"))
        assertTrue(requests.begin("first")!!.canceled.get())
        assertTrue(requests.cancel("second"))
        time = 180_001L
        assertTrue(requests.cancel("third"))
    }
    @Test fun `duplicate invalid and capacity overflow requests do not replace active calls`() {
        val requests = PreviewRequests(capacity = 1)
        val first = requests.begin("first")!!
        assertNull(requests.begin("first"))
        assertNull(requests.begin("second"))
        assertNull(requests.begin(""))
        requests.cancelAll()
        assertTrue(first.canceled.get())
        requests.finish("first", first)
        assertNotNull(requests.begin("second"))
    }
}
