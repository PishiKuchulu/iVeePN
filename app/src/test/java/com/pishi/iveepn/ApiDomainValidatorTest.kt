package com.pishi.iveepn

import com.pishi.iveepn.data.ApiDomainValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API domain validator guards the reserve-domain rotation: the access
 * token is sent to every domain in the list, so only strict https URLs
 * with a sane hostname and a real port may pass.
 */
class ApiDomainValidatorTest {

    @Test
    fun acceptsPlainHttpsDomains() {
        assertTrue(ApiDomainValidator.isValid("https://antpeak.com"))
        assertTrue(ApiDomainValidator.isValid("https://hibchr.com"))
        assertTrue(ApiDomainValidator.isValid("https://tronlit.com"))
    }

    @Test
    fun acceptsSubdomainHosts() {
        assertTrue(ApiDomainValidator.isValid("https://api.antpeak.com"))
        assertTrue(ApiDomainValidator.isValid("https://a.b.antpeak.com"))
        assertTrue(ApiDomainValidator.isValid("https://my-api.example-domain.co.uk"))
    }

    @Test
    fun acceptsExplicitValidPort() {
        assertTrue(ApiDomainValidator.isValid("https://antpeak.com:443"))
        assertTrue(ApiDomainValidator.isValid("https://antpeak.com:1"))
        assertTrue(ApiDomainValidator.isValid("https://antpeak.com:8443"))
        assertTrue(ApiDomainValidator.isValid("https://antpeak.com:65535"))
    }

    @Test
    fun rejectsNonHttpsSchemes() {
        assertFalse(ApiDomainValidator.isValid("http://antpeak.com"))
        assertFalse(ApiDomainValidator.isValid("ftp://antpeak.com"))
        assertFalse(ApiDomainValidator.isValid("//antpeak.com"))
    }

    @Test
    fun rejectsBareHostWithoutScheme() {
        assertFalse(ApiDomainValidator.isValid("antpeak.com"))
        assertFalse(ApiDomainValidator.isValid("www.antpeak.com"))
        assertFalse(ApiDomainValidator.isValid("https://"))
        assertFalse(ApiDomainValidator.isValid(""))
    }

    @Test
    fun rejectsSpacesAndUnderscoreHosts() {
        assertFalse(ApiDomainValidator.isValid("https://ant peak.com"))
        assertFalse(ApiDomainValidator.isValid("https://antpeak .com"))
        assertFalse(ApiDomainValidator.isValid("https://ant_peak.com"))
        assertFalse(ApiDomainValidator.isValid("https://my_host.example.com"))
    }

    @Test
    fun rejectsOutOfRangePorts() {
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com:0"))
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com:65536"))
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com:99999"))
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com:abc"))
    }

    @Test
    fun rejectsTrailingJunk() {
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com/path"))
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com?format=json"))
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com:443/extra"))
        assertFalse(ApiDomainValidator.isValid("https://antpeak.com.evil.example/path"))
    }

    @Test
    fun rejectsIpLiteralHosts() {
        assertFalse(ApiDomainValidator.isValid("https://127.0.0.1"))
        assertFalse(ApiDomainValidator.isValid("https://127.0.0.1:8443"))
        assertFalse(ApiDomainValidator.isValid("https://192.168.1.1"))
        assertFalse(ApiDomainValidator.isValid("https://10.0.0.5"))
        assertFalse(ApiDomainValidator.isValid("https://172.16.0.9"))
    }

    @Test
    fun rejectsLocalhostAndIpv6Literals() {
        assertFalse(ApiDomainValidator.isValid("https://localhost"))
        assertFalse(ApiDomainValidator.isValid("https://localhost:8443"))
        assertFalse(ApiDomainValidator.isValid("https://[::1]"))
        assertFalse(ApiDomainValidator.isValid("https://[2001:db8::1]"))
    }
}
