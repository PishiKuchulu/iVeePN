package com.pishi.iveepn.data

/**
 * The one shared browser user-agent string for the VeePN backend calls
 * and the upstream CONNECT/exit-IP requests (Chrome 130, identical to the
 * desktop client). Single definition so the API and tunnel fingerprints
 * cannot drift apart.
 */
object UserAgent {
    const val VALUE = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
}
