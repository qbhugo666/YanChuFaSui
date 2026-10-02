package com.voicecontrol.app

import java.io.File
import org.junit.Assume.assumeTrue

/** 本机实验数据不公开；缺少它们时仅跳过对应实验，生产词表仍须存在。 */
internal object OptionalExperimentFiles {
    fun requireFile(path: String): File {
        val file = listOf(File(path), File("../$path")).firstOrNull { it.isFile }
        val privateFixture = path.startsWith("_test/") || path.endsWith(".onnx") || path.endsWith("_meta.json")
        if (privateFixture) assumeTrue("Optional local experiment fixture unavailable: $path", file != null)
        return requireNotNull(file) { "Required production fixture unavailable: $path" }
    }
}
