package org.hyperledger.identus.walletsdk.domain.models

public enum class DIDState {
    PUBLISHED,  // 已发布到链上
    UNPUBLISHED, // 未发布到链上

    LISTENING, // 监听状态，用于接收消息
}