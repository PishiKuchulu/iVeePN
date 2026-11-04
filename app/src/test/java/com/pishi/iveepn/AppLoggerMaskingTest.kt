package com.pishi.iveepn

import com.pishi.iveepn.data.AppLogger
import org.junit.Assert.assertEquals
import org.junit.Test

/** V-20: email masking in [AppLogger] must catch unusual-but-valid shapes. */
class AppLoggerMaskingTest {

    @Test
    fun plainAddressIsMasked() {
        assertEquals(
            "login for j***@e***.com failed",
            AppLogger.maskEmails("login for john.doe@example.com failed"),
        )
    }

    @Test
    fun subdomainAddressIsMasked() {
        assertEquals(
            "u***@m***.uk",
            AppLogger.maskEmails("user@mail.example.co.uk"),
        )
    }

    @Test
    fun plusAddressingIsMasked() {
        assertEquals(
            "u***@e***.com",
            AppLogger.maskEmails("user+promo@example.com"),
        )
    }

    @Test
    fun shortTldIsMasked() {
        assertEquals(
            "a***@b***.io",
            AppLogger.maskEmails("a@b.io"),
        )
    }

    @Test
    fun localPartStartingWithHyphenIsMasked() {
        assertEquals(
            "-***@e***.com",
            AppLogger.maskEmails("-john@example.com"),
        )
    }

    @Test
    fun localPartStartingWithDotIsMasked() {
        assertEquals(
            ".***@e***.com",
            AppLogger.maskEmails(".john@example.com"),
        )
    }

    @Test
    fun messageWithoutEmailIsUnchanged() {
        val message = "connection to 185.10.2.3:443 timed out"
        assertEquals(message, AppLogger.maskEmails(message))
    }

    @Test
    fun userAtHostWithoutTldIsNotAnEmail() {
        val message = "proxy user@host rejected the request"
        assertEquals(message, AppLogger.maskEmails(message))
    }
}
