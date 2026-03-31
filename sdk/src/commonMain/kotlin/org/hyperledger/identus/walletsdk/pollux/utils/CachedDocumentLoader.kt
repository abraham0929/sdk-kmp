package org.hyperledger.identus.walletsdk.pollux.utils

import com.apicatalog.jsonld.JsonLdError
import com.apicatalog.jsonld.JsonLdErrorCode
import com.apicatalog.jsonld.document.Document
import com.apicatalog.jsonld.document.JsonDocument
import com.apicatalog.jsonld.http.media.MediaType
import com.apicatalog.jsonld.loader.DocumentLoader
import com.apicatalog.jsonld.loader.DocumentLoaderOptions
import com.apicatalog.jsonld.loader.HttpLoader
import java.net.URI

/**
 * A [DocumentLoader] that serves JSON-LD context documents from an in-memory
 * cache loaded upfront, falling back to network for any uncached URL.
 *
 * All four standard context files are read eagerly at construction time,
 * so there are zero file I/O calls during JSON-LD processing.
 *
 * Usage (Android) — pass all four files at once:
 * ```kotlin
 * val loader = CachedDocumentLoader(
 *     contextStreams = mapOf(
 *         CachedDocumentLoader.W3C_CREDENTIALS_V1    to context.assets.open("contexts/w3_credentials_v1.json"),
 *         CachedDocumentLoader.JWS_2020_V1           to context.assets.open("contexts/jws_2020_v1.json"),
 *         CachedDocumentLoader.ED25519_2020_V1       to context.assets.open("contexts/ed25519_2020_v1.json"),
 *         CachedDocumentLoader.BBS_V1                to context.assets.open("contexts/bbs_v1.json"),
 *     )
 * )
 * val pollux = PolluxImpl(apollo = apollo, castor = castor, documentLoader = loader)
 * ```
 *
 * Place the following files in your Android app's `assets/contexts/` directory:
 * - w3_credentials_v1.json       <- https://www.w3.org/2018/credentials/v1
 * - jws_2020_v1.json             <- https://w3id.org/security/suites/jws-2020/v1
 * - ed25519_2020_v1.json         <- https://w3id.org/security/suites/ed25519-2020/v1
 * - bbs_v1.json                  <- https://w3id.org/security/bbs/v1
 *
 * Download commands:
 * ```bash
 * curl -L -H "Accept: application/ld+json" https://www.w3.org/2018/credentials/v1           -o w3_credentials_v1.json
 * curl -L -H "Accept: application/ld+json" https://w3id.org/security/suites/jws-2020/v1     -o jws_2020_v1.json
 * curl -L -H "Accept: application/ld+json" https://w3id.org/security/suites/ed25519-2020/v1 -o ed25519_2020_v1.json
 * curl -L -H "Accept: application/ld+json" https://w3id.org/security/bbs/v1                 -o bbs_v1.json
 * ```
 *
 * @param contextStreams Map of context URL to the corresponding [java.io.InputStream].
 *                      Use the URL constants defined in [CachedDocumentLoader.Companion] as keys.
 */
class CachedDocumentLoader(
    contextStreams: Map<String, java.io.InputStream>
) : DocumentLoader {

    // Read all streams eagerly into memory at construction time.
    // Stored as byte arrays so the same content can be re-read multiple times
    // (InputStream can only be consumed once).
    private val cache: Map<String, ByteArray> = contextStreams.mapValues { (url, stream) ->
        stream.use { it.readBytes() }.also {
            System.err.println("[CachedDocumentLoader] Loaded ${it.size} bytes for: $url")
        }
    }

    // Network fallback for URLs not present in the local cache
    private val networkLoader = HttpLoader(CustomHttpClient())

    override fun loadDocument(url: URI, options: DocumentLoaderOptions): Document {
        val urlStr = url.toString()
        System.err.println("[CachedDocumentLoader] loadDocument: $urlStr")

        // 1. Serve from in-memory cache
        val bytes = cache[urlStr]
        if (bytes != null) {
            System.err.println("[CachedDocumentLoader] ✅ Cache hit: $urlStr (${bytes.size} bytes)")
            return JsonDocument.of(MediaType.JSON_LD, bytes.inputStream())
        }

        // 2. Fall back to network
        System.err.println("[CachedDocumentLoader] 🌐 Cache miss, fetching from network: $urlStr")
        return try {
            networkLoader.loadDocument(url, options)
        } catch (e: Exception) {
            System.err.println("[CachedDocumentLoader] ❌ Network fallback failed: ${e.message}")
            throw JsonLdError(JsonLdErrorCode.LOADING_REMOTE_CONTEXT_FAILED, e)
        }
    }

    companion object {
        // Well-known context URL constants — use these as keys in contextStreams
        const val W3C_CREDENTIALS_V1  = "https://www.w3.org/2018/credentials/v1"
        const val JWS_2020_V1         = "https://w3id.org/security/suites/jws-2020/v1"
        const val ED25519_2020_V1     = "https://w3id.org/security/suites/ed25519-2020/v1"
        const val BBS_V1              = "https://w3id.org/security/bbs/v1"
    }
}