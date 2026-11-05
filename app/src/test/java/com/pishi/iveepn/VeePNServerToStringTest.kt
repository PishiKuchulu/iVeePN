package com.pishi.iveepn

import com.pishi.iveepn.data.model.VeePNServer
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * A server entry carries the guest credentials for an upstream proxy.
 * Its string form ends up in logs and debug output, so it must never
 * expose the credential value.
 */
class VeePNServerToStringTest {

    @Test
    fun toStringDoesNotContainTheCredentialValue() {
        val marker = "unit-test-credential-marker"
        // Positional arguments on purpose.
        val server = VeePNServer(
            listOf("203.0.113.10"),
            8443,
            "guest-user",
            marker,
        )
        assertFalse(server.toString().contains(marker))
    }
}
