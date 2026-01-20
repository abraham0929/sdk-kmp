package org.hyperledger.identus.walletsdk.mercury.resolvers

import io.iohk.atala.prism.didcomm.didpeer.core.fromMulticodec
import io.iohk.atala.prism.didcomm.didpeer.core.toJwk
import io.iohk.atala.prism.didcomm.didpeer.multibase.MultiBase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.didcommx.didcomm.common.VerificationMaterial
import org.didcommx.didcomm.common.VerificationMaterialFormat
import org.didcommx.didcomm.common.VerificationMethodType
import org.didcommx.didcomm.diddoc.DIDCommService
import org.didcommx.didcomm.diddoc.DIDDoc
import org.didcommx.didcomm.diddoc.DIDDocResolver
import org.didcommx.didcomm.diddoc.VerificationMethod
import org.hyperledger.identus.walletsdk.domain.DIDCOMM_MESSAGING
import org.hyperledger.identus.walletsdk.domain.buildingblocks.Castor
import org.hyperledger.identus.walletsdk.domain.models.CastorError
import org.hyperledger.identus.walletsdk.domain.models.Curve
import org.hyperledger.identus.walletsdk.domain.models.DIDDocument
import org.hyperledger.identus.walletsdk.domain.models.OctetPublicKey
import org.hyperledger.identus.walletsdk.mercury.CRV
import org.hyperledger.identus.walletsdk.mercury.MULTIBASE_BYTES_SIZE
import org.hyperledger.identus.walletsdk.mercury.X
import java.util.Optional

/**
 * A resolver that resolves a Decentralized Identifier (DID) to its corresponding DID Document.
 *
 * @param castor The instance of Castor used to resolve DIDs.
 */
class DIDCommDIDResolver(val castor: Castor) : DIDDocResolver {
    /**
     * Resolves a DID to its corresponding DID Document.
     *
     * @param did The DID to resolve.
     * @return An optional containing the DID Document associated with the DID, or an empty optional if the document cannot be retrieved.
     * @throws [CastorError.InvalidJWKKeysError] if the JWK keys are not in a valid format.
     */
    @Throws(CastorError.InvalidJWKKeysError::class)
    override fun resolve(did: String): Optional<DIDDoc> {
        return runBlocking {
            val doc = castor.resolveDID(did)
            val authentications = mutableListOf<String>()
            val keyAgreements = mutableListOf<String>()
            val services = mutableListOf<DIDCommService>()
            val verificationMethods = mutableListOf<VerificationMethod>()

            doc.coreProperties.forEach { coreProperty ->
                val methods = when (coreProperty) {
                    is DIDDocument.Authentication -> coreProperty.verificationMethods
                    is DIDDocument.AssertionMethod -> coreProperty.verificationMethods
                    is DIDDocument.KeyAgreement -> coreProperty.verificationMethods
                    is DIDDocument.CapabilityInvocation -> coreProperty.verificationMethods
                    is DIDDocument.CapabilityDelegation -> coreProperty.verificationMethods
                    else -> emptyArray()
                }

                methods.forEach { method ->
                    val curve = extractCurveFromPublicKey(method)

                    if (curve === Curve.ED25519 || curve === Curve.SECP256K1) {
                        authentications.add(method.id.string())
                    }

                    if (curve === Curve.X25519) {
                        keyAgreements.add(method.id.string())
                    }

                    // In this method we need to send th key as JWK while sometimes we get it as
                    // MultiBase, therefore the following lines of code convert it
                    val publicKeyJWK: Map<String, String>
                    if (method.publicKeyMultibase != null) {
                        var keyBytes = MultiBase.decode(method.publicKeyMultibase)
                        // In the case of MultiBase make sure to remove the MultiCodec from the ByteArray
                        if (keyBytes.size == MULTIBASE_BYTES_SIZE) {
                            keyBytes = fromMulticodec(keyBytes).second
                        }
                        publicKeyJWK = when (curve) {
                            Curve.ED25519 -> {
                                toJwk(
                                    keyBytes,
                                    io.iohk.atala.prism.didcomm.didpeer.VerificationMethodTypeAuthentication.JsonWebKey2020
                                )
                            }
                            Curve.SECP256K1 -> {
                                // 处理 SECP256K1 曲线
                                toJwk(
                                    keyBytes,
                                    io.iohk.atala.prism.didcomm.didpeer.VerificationMethodTypeAuthentication.JsonWebKey2020
                                )
                            }
                            Curve.X25519 -> {
                                toJwk(
                                    keyBytes,
                                    io.iohk.atala.prism.didcomm.didpeer.VerificationMethodTypeAgreement.JsonWebKey2020
                                )
                            }
                            else -> throw RuntimeException("invalid curve type: ${curve.value}")
                        }
                    } else if (method.publicKeyJwk != null) {
                        publicKeyJWK = method.publicKeyJwk
                    } else {
                        throw RuntimeException("invalid public key format method: ${method.id.string()}")
                    }
                    publicKeyJWK[CRV]?.let { crv ->
                        publicKeyJWK[X]?.let { x ->
                            verificationMethods.add(
                                VerificationMethod(
                                    id = method.id.string(),
                                    controller = method.controller.toString(),
                                    type = VerificationMethodType.JSON_WEB_KEY_2020,
                                    verificationMaterial = VerificationMaterial(
                                        VerificationMaterialFormat.JWK,
                                        Json.encodeToString(
                                            OctetPublicKey(
                                                crv = crv,
                                                x = x
                                            )
                                        )
                                    )
                                )
                            )
                        } ?: throw CastorError.InvalidJWKKeysError()
                    } ?: throw CastorError.InvalidJWKKeysError()
                }

                if (coreProperty is DIDDocument.Service && coreProperty.type.contains(DIDCOMM_MESSAGING)) {
                    services.add(
                        DIDCommService(
                            id = coreProperty.id,
                            serviceEndpoint = coreProperty.serviceEndpoint.uri,
                            routingKeys = coreProperty.serviceEndpoint.routingKeys?.toList() ?: emptyList(),
                            accept = coreProperty.serviceEndpoint.accept?.toList() ?: emptyList()
                        )
                    )
                }
            }

            Optional.of(
                DIDDoc(
                    doc.id.toString(),
                    keyAgreements,
                    authentications,
                    verificationMethods,
                    services
                )
            )
        }
    }
    private fun extractCurveFromPublicKey(method: DIDDocument.VerificationMethod): Curve {
        try {
            val curveFromType = DIDDocument.VerificationMethod.getCurveByType(method.type)
            if (curveFromType != null) {
                return curveFromType
            }
        } catch (e: CastorError) {
            // 如果 getCurveByType 抛出异常，继续执行下面的逻辑
        }

        // 尝试从 publicKeyJwk 的 crv 字段获取曲线
        if (method.publicKeyJwk != null) {
            val crv = method.publicKeyJwk!!["crv"]
            if (crv != null) {
                return when (crv.lowercase()) {
                    "ed25519" -> Curve.ED25519
                    "x25519" -> Curve.X25519
                    "secp256k1" -> Curve.SECP256K1
                    else -> throw CastorError.InvalidKeyError()
                }
            }
        }

        // 如果没有JWK，尝试从 publicKeyMultibase 解码后推断曲线
        if (method.publicKeyMultibase != null) {
            val keyBytes = MultiBase.decode(method.publicKeyMultibase)
            val (_, decodedBytes) = if (keyBytes.size > 2) {
                fromMulticodec(keyBytes)
            } else {
                Pair(null, keyBytes)
            }

            // 根据字节长度推断曲线
            return when (decodedBytes.size) {
                32 -> Curve.ED25519 // Ed25519 keys are typically 32 bytes
                33 -> {
                    // 对于33字节的情况，需要进一步区分是X25519还是SECP256K1压缩公钥
                    // 检查是否为有效的SECP256K1压缩公钥格式（前缀必须是0x02或0x03）
                    if (isCompressedSecp256k1(decodedBytes)) {
                        Curve.SECP256K1
                    } else {
                        // 否则假设为X25519（可能带前缀）
                        Curve.X25519
                    }
                }
                65 -> Curve.SECP256K1 // Uncompressed secp256k1
                else -> {
                    // 如果无法从长度判断，使用原始方法作为后备
                    throw CastorError.InvalidKeyError()
                }
            }
        }
        throw CastorError.InvalidKeyError()
    }

    private fun isCompressedSecp256k1(key: ByteArray): Boolean {
        return key.size == 33 && (key[0] == 0x02.toByte() || key[0] == 0x03.toByte())
    }
}
