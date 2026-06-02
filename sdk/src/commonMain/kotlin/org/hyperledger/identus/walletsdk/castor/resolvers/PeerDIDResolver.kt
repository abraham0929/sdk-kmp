package org.hyperledger.identus.walletsdk.castor.resolvers

import org.hyperledger.identus.walletsdk.castor.PEER
import org.hyperledger.identus.walletsdk.castor.shared.CastorShared
import org.hyperledger.identus.walletsdk.domain.models.DIDDocument
import org.hyperledger.identus.walletsdk.domain.models.DIDResolver

/**
 * The [PeerDIDResolver] class is an implementation of the [DIDResolver] interface for resolving DID document using the Peer DID method.
 *
 * @see DIDResolver
 */
class PeerDIDResolver : DIDResolver {
    override val method: String = PEER

    // did:peer 解析是对 DID 字符串的纯解码(无网络),结果不可变。
    // 但消息收发(尤其 live-mode 的 pickup 轮询)会对同一批 host/mediator peer DID 反复解析,
    // 每次重新解码会持续消耗 CPU/内存。这里做一个有界的进程内缓存,避免重复解析。
    private val cache = java.util.concurrent.ConcurrentHashMap<String, DIDDocument>()

    /**
     * Resolves a DID document using the Peer DID method.
     *
     * @param didString the string representation of the DID
     * @return the resolved DID document
     */
    override suspend fun resolve(didString: String): DIDDocument {
        cache[didString]?.let { return it }
        val document = CastorShared.resolvePeerDID(didString)
        // peer DID 不可变,可永久缓存;加一个上限防止极端情况下无界增长。
        if (cache.size >= MAX_CACHE_SIZE) {
            cache.clear()
        }
        cache[didString] = document
        return document
    }

    companion object {
        private const val MAX_CACHE_SIZE = 512
    }
}
