package org.hyperledger.identus.walletsdk.pollux.utils

import com.apicatalog.jsonld.JsonLdError
import com.apicatalog.jsonld.JsonLdErrorCode
import com.apicatalog.jsonld.http.HttpClient
import com.apicatalog.jsonld.http.HttpResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.InputStream
import java.net.URI
import java.time.Duration
import java.util.Optional
import java.util.concurrent.TimeUnit

class CustomHttpClient : HttpClient {

    private val okHttpClient: OkHttpClient

    constructor() {
        this.okHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    constructor(client: OkHttpClient) {
        this.okHttpClient = client
    }

    override fun send(targetUri: URI, requestProfile: String): HttpResponse {
        return try {
            val request = Request.Builder()
                .url(targetUri.toURL())
                .header("Accept", requestProfile)
                .header("User-Agent", USER_AGENT)   // 添加浏览器 User-Agent
                .build()

            val response = okHttpClient.newCall(request).execute()
            HttpResponseImpl(response)
        } catch (e: Exception) {
            throw JsonLdError(JsonLdErrorCode.LOADING_DOCUMENT_FAILED, e)
        }
    }

    override fun timeout(timeout: Duration?): HttpClient {
        val timeoutMillis = timeout?.toMillis() ?: 0L
        val newClient = okHttpClient.newBuilder()
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
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
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}