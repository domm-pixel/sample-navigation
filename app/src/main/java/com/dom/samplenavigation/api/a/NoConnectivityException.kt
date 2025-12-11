package com.dom.samplenavigation.api.a

import java.io.IOException

class NoConnectivityException  : IOException() {
    override val message: String
        get() = """
            데이터 요청에 실패 하였습니다.
            사용중인 네트워크 상태를 확인해주세요.
            """.trimIndent()
}