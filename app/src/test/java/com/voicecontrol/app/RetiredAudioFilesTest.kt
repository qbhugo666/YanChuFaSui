package com.voicecontrol.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RetiredAudioFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `只删除二审原件临时副本与标记_普通模型和用户数据逐字保留`() {
        val root = temp.newFolder()
        val retired = listOf("sensevoice_enc.onnx", "nn_number_pair4_10.onnx",
            "sensevoice_enc.onnx.part", "libonnxruntime_120.so", "audio_decision_asset_hashes.properties.part")
        val kept = listOf("sensevoice.int8.onnx", "silero_vad.onnx", "tokens.txt", "commands.json",
            "custom_bindings.json", "custom_vocab.json", "usage_log.json", "scroll_mech_cache.json", "other.onnx")
        (retired + kept).forEach { File(root, it).writeText("keep:$it") }
        val expectedBytes = retired.sumOf { File(root, it).length() }
        val result = RetiredAudioFiles.removeFrom(root)
        assertEquals(expectedBytes, result.bytes)
        assertEquals(retired.toSet(), result.removed.toSet())
        assertTrue(result.failed.isEmpty())
        retired.forEach { assertFalse(File(root, it).exists()) }
        kept.forEach { assertEquals("keep:$it", File(root, it).readText()) }
    }

    @Test fun `重复启动清理幂等_不删除同名目录及其内容`() {
        val root = temp.newFolder()
        val directory = File(root, "sensevoice_enc.onnx").apply { mkdir() }
        val child = File(directory, "data").apply { writeText("preserve") }
        File(root, "audio_decision_head.onnx").writeText("old")
        assertEquals(3L, RetiredAudioFiles.removeFrom(root).bytes)
        assertEquals(0L, RetiredAudioFiles.removeFrom(root).bytes)
        assertEquals("preserve", child.readText())
    }

    @Test fun `只处理根目录白名单_嵌套备份与配置目录不动`() {
        val root = temp.newFolder()
        val backups = File(root, "backup").apply { mkdir() }
        val old = File(backups, "sensevoice_enc.onnx").apply { writeText("backup") }
        assertEquals(0L, RetiredAudioFiles.removeFrom(root).bytes)
        assertEquals("backup", old.readText())
        assertTrue(backups.isDirectory)
    }
}
