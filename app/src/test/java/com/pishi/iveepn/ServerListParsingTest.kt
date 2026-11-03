package com.pishi.iveepn

import com.pishi.iveepn.data.VeePNServerListParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server-list parsing. The desktop API answers POST /v3/server/list/ with
 * a top-level JSON array of entries shaped
 * {username, password, port, addresses: [...], ...}; only entries with a
 * non-empty address list and a port are usable.
 */
class ServerListParsingTest {

    @Test
    fun validEntriesAreKeptWithTheirFields() {
        val json = """
            [
              {"addresses": ["185.0.0.1", "proxy1.example.com"], "port": 443,
               "username": "u1", "password": "p1", "region": "de"},
              {"addresses": ["185.0.0.2"], "port": 8443,
               "username": "u2", "password": "p2"}
            ]
        """.trimIndent()
        val servers = VeePNServerListParser.parse(json)
        assertEquals(2, servers.size)
        assertEquals(listOf("185.0.0.1", "proxy1.example.com"), servers[0].addresses)
        assertEquals(443, servers[0].port)
        assertEquals("u1", servers[0].username)
        assertEquals("p1", servers[0].password)
        assertEquals(8443, servers[1].port)
    }

    @Test
    fun entriesMissingAddressesOrPortAreDropped() {
        val json = """
            [
              {"port": 443, "username": "u1", "password": "p1"},
              {"addresses": [], "port": 443, "username": "u2", "password": "p2"},
              {"addresses": ["185.0.0.3"], "username": "u3", "password": "p3"},
              {"addresses": ["185.0.0.4"], "port": 443, "username": "u4", "password": "p4"}
            ]
        """.trimIndent()
        val servers = VeePNServerListParser.parse(json)
        assertEquals(1, servers.size)
        assertEquals(listOf("185.0.0.4"), servers[0].addresses)
        assertEquals("u4", servers[0].username)
    }

    @Test
    fun allMalformedEntriesYieldEmptyList() {
        val json = """
            [
              {"port": 443},
              {"addresses": []},
              {"addresses": ["185.0.0.5"]},
              "not-an-object",
              42
            ]
        """.trimIndent()
        assertTrue(VeePNServerListParser.parse(json).isEmpty())
    }

    @Test
    fun nonJsonAndNonArrayInputYieldEmptyList() {
        assertTrue(VeePNServerListParser.parse("not json at all").isEmpty())
        assertTrue(VeePNServerListParser.parse("").isEmpty())
        assertTrue(VeePNServerListParser.parse("{}").isEmpty())
        assertTrue(VeePNServerListParser.parse("[]").isEmpty())
    }
}
