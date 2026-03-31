package org.hyperledger.identus.walletsdk.pollux.utils

import com.apicatalog.jsonld.JsonLdError
import com.apicatalog.jsonld.JsonLdErrorCode
import com.apicatalog.jsonld.http.HttpClient
import com.apicatalog.jsonld.http.HttpResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * A custom [HttpClient] implementation for JSON-LD document loading.
 *
 * Replaces the default [com.apicatalog.jsonld.loader.DefaultHttpLoader] to:
 * - Bypass system proxies (avoids CONNECT tunnel issues with Proxyman / Charles)
 * - Set a browser-like User-Agent to reduce server-side bot blocking
 * - Apply sensible connect / read / call timeouts
 */
class CustomHttpClient : HttpClient {

    private val okHttpClient: OkHttpClient

    constructor() {
        this.okHttpClient = buildDefaultClient()
    }

    constructor(client: OkHttpClient) {
        this.okHttpClient = client
    }

    override fun send(targetUri: URI, requestProfile: String): HttpResponse {
        return try {
            val request = Request.Builder()
                .url(targetUri.toURL())
                .header("Accept", requestProfile)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = okHttpClient.newCall(request).execute()
            HttpResponseImpl(response)
        } catch (e: Exception) {
            throw JsonLdError(JsonLdErrorCode.LOADING_DOCUMENT_FAILED, e)
        }
    }

    override fun timeout(timeout: Duration?): HttpClient {
        val timeoutMillis = timeout?.toMillis() ?: 0L
        // Guard: OkHttp treats 0 as "no timeout" (infinite wait), so enforce a minimum
        val effectiveTimeout = if (timeoutMillis <= 0L) DEFAULT_TIMEOUT_MS else timeoutMillis
        val newClient = okHttpClient.newBuilder()
            .connectTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .readTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .writeTimeout(effectiveTimeout, TimeUnit.MILLISECONDS)
            .callTimeout(effectiveTimeout * 2, TimeUnit.MILLISECONDS)
            .build()
        return CustomHttpClient(newClient)
    }

    private class HttpResponseImpl(private val response: Response) : HttpResponse {
        override fun statusCode(): Int = response.code
        override fun body(): InputStream = response.body?.byteStream() ?: error("No body")
        override fun close() = response.close()
        override fun links(): Collection<String> = response.headers("link")
        override fun contentType(): Optional<String> = firstValue(response.headers("content-type"))
        override fun location(): Optional<String> = firstValue(response.headers("location"))

        private fun firstValue(values: List<String>): Optional<String> =
            if (values.isEmpty()) Optional.empty() else Optional.of(values[0])
    }

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30_000L

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private fun buildDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                // Explicitly bypass system proxy to prevent CONNECT tunnel issues
                .proxy(Proxy.NO_PROXY)
                // Also override ProxySelector to prevent OkHttp reading JVM system proxy properties
                .proxySelector(NoProxySelector)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(DEFAULT_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
                .build()
        }

        /**
         * A no-op [ProxySelector] that always returns [Proxy.NO_PROXY].
         * Prevents OkHttp from picking up system-level proxy settings
         * (e.g. those injected by Proxyman or Charles Proxy during development).
         */
        private object NoProxySelector : ProxySelector() {
            private val NO_PROXY_LIST = listOf(Proxy.NO_PROXY)
            override fun select(uri: URI?): List<Proxy> = NO_PROXY_LIST
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
                // No-op: direct connection failure has no fallback proxy to report
            }
        }
    }
}