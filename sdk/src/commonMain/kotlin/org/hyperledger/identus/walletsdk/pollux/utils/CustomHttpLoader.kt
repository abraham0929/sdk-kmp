package org.hyperledger.identus.walletsdk.pollux.utils

import com.apicatalog.jsonld.loader.HttpLoader

class CustomHttpLoader : HttpLoader {

    constructor() : super(CustomHttpClient())

    constructor(maxRedirections: Int) : super(CustomHttpClient(), maxRedirections)
}

// 使用 object 声明单例，或直接在伴生对象中持有实例
object GlobalHttpLoader {
    val instance: CustomHttpLoader by lazy { CustomHttpLoader() }
}