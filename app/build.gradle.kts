import java.util.Properties

import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun sha256Hex(input: java.io.InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    input.buffered().use { stream ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

// 正式签名（keystore.properties 不入库，密钥与密码只留在本机 + HANDOFF 备份说明）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// 2026-10-03：二审已移除，仅留本机实验原件；不再装进手机。
val decisionAssetNames = listOf(
    "sensevoice_enc.onnx", "audio_decision_head.onnx",
    "m6_head_packed.onnx", "m6_head_meta.json",
    "cursor_review_head.onnx", "cursor_review_meta.json",
    "nn_number_head.onnx", "nn_number_head_meta.json",
    "nn_number_segments.onnx", "nn_number_segments_meta.json",
    "nn_number_pair4_10.onnx", "nn_number_pair4_10_meta.json",
    "libonnxruntime_120_arm32.so", "libonnxruntime_120_arm64.so",
)
val speechAssetNames = listOf("sensevoice.int8.onnx", "silero_vad.onnx", "tokens.txt", "commands.json")

android {
    namespace = "com.voicecontrol.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicecontrol.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 157
        versionName = "0.58.3"

        // 只打包真机需要的两种 ARM 架构，减小 APK 体积
        // （arm64-v8a = 现代手机；armeabi-v7a = 老旧 32 位手机）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Debug 包保留 adb 注入入口，供开发期真机诊断使用。
            manifestPlaceholders["voiceServiceExported"] = "true"
        }
        release {
            isMinifyEnabled = false
            // 生产包的麦克风服务只允许本 App 自己启动。
            manifestPlaceholders["voiceServiceExported"] = "false"
            // 密钥文件缺失时不签名（构建出的包无法安装，等于显式报错），绝不静默回退 debug 签名
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // 大模型文件放在 assets，禁止压缩（模型本身已是压缩格式，再压会拖慢加载）
    androidResources {
        noCompress += listOf("onnx", "txt", "fst", "so")
        ignoreAssetsPatterns += decisionAssetNames + "audio_decision_assets.sha256"
        // 发布隐私检查：开发录音夹具只保留在本机，不进入安装包。
        ignoreAssetsPatterns += listOf("*.wav", "*.mp3", "*.m4a", "*.pcm")
    }

    // 安全回退包：sherpa 原始 AAR 的 ORT 1.27.1 必须供主语音链路使用。
    packaging {
        jniLibs.pickFirsts += "lib/**/libonnxruntime.so"
        // Java API留作旧代码的编译依赖；二审专用JNI桥已无运行调用。
        jniLibs.excludes += "lib/**/libonnxruntime4j_jni.so"
    }

}
// AudioDecision 的大模型与 ORT 1.20 so 是本地生成/准备的文件（不进 Git）。
// 构建前明确检查并生成内容指纹，避免 APK 静默缺模型，或同长度新模型被旧缓存误认为未变化。
// 2026-09-30 D 阶段：m6 全类小头（shadow 只算提案不改变作）一并纳入指纹与包校验。
val decisionAssetSource = file("src/main/assets")
val generatedDecisionAssets = layout.buildDirectory.dir("generated/audioDecisionAssets")
// 原二审校验/清单任务保留供离线实验；退出生产preBuild和assets来源。

val verifyDecisionAssets = tasks.register("verifyAudioDecisionAssets") {
    doLast {
        val missing = decisionAssetNames.filter { !decisionAssetSource.resolve(it).isFile }
        check(missing.isEmpty()) {
            "AudioDecision 构建资源缺失：${missing.joinToString()}。请恢复本机生成的 encoder/head 和 ORT 1.20 ARM so 到 app/src/main/assets。"
        }
        check(decisionAssetSource.resolve("sensevoice_enc.onnx").length() > 100_000_000L) {
            "sensevoice_enc.onnx 体积异常，拒绝打出无法工作的增强识别包。"
        }
        check(decisionAssetSource.resolve("audio_decision_head.onnx").length() in 32_000L..1_000_000L) {
            "audio_decision_head.onnx 体积异常，拒绝打包。"
        }
        // m6 全类小头（2026-09-30 D）：单文件 ONNX（权重内嵌），281KB 级；异常体积拒绝打包
        check(decisionAssetSource.resolve("m6_head_packed.onnx").length() in 100_000L..2_000_000L) {
            "m6_head_packed.onnx 体积异常，拒绝打包。"
        }
        check(decisionAssetSource.resolve("cursor_review_head.onnx").length() in 32_000L..400_000L) {
            "cursor_review_head.onnx 体积异常，拒绝打包。"
        }
        val cursorMeta = groovy.json.JsonSlurper().parse(decisionAssetSource.resolve("cursor_review_meta.json")) as Map<*, *>
        check(cursorMeta["labels"] == listOf("text_cursor_left", "text_cursor_right", "other")) {
            "光标模型类别顺序异常，拒绝打包。"
        }
        check(cursorMeta["model_sha256"] == decisionAssetSource.resolve("cursor_review_head.onnx").inputStream().use(::sha256Hex)) {
            "光标模型与训练元数据指纹不一致，拒绝打包。"
        }
        val numFile = decisionAssetSource.resolve("nn_number_head.onnx")
        val numMeta = groovy.json.JsonSlurper().parse(decisionAssetSource.resolve("nn_number_head_meta.json")) as Map<*, *>
        check(numMeta["labels"] == (1..30).map(Int::toString) +
            listOf("out_of_range", "non_number_click", "other")) { "数字模型标签顺序异常，拒绝打包。" }
        check((numMeta["model_bytes"] as Number).toLong() == numFile.length() &&
            numFile.length() in 100_000L..2_000_000L &&
            numMeta["model_sha256"] == numFile.inputStream().use(::sha256Hex)) {
            "数字模型权重或元数据不完整，拒绝打包。"
        }
        for (name in decisionAssetNames.filter { it.endsWith(".so") }) {
            check(decisionAssetSource.resolve(name).length() > 5_000_000L) { "$name 体积异常，拒绝打包。" }
        }
        val segmentFile = decisionAssetSource.resolve("nn_number_segments.onnx")
        val segmentMeta = groovy.json.JsonSlurper().parse(decisionAssetSource.resolve("nn_number_segments_meta.json")) as Map<*, *>
        check(segmentMeta["labels"] == numMeta["labels"] &&
            (segmentMeta["model_bytes"] as Number).toLong() == segmentFile.length() &&
            segmentFile.length() in 100_000L..2_000_000L &&
            segmentMeta["model_sha256"] == segmentFile.inputStream().use(::sha256Hex) &&
            segmentMeta["encoder_sha256"] == decisionAssetSource.resolve("sensevoice_enc.onnx").inputStream().use(::sha256Hex) &&
            (segmentMeta["input_dimension"] as Number).toInt() == 1536 &&
            (segmentMeta["control_prefix_rows"] as Number).toInt() == 4 &&
            (segmentMeta["bins"] as Number).toInt() == 3 &&
            (segmentMeta["threshold"] as Number).toDouble() == .99) {
            "分段数字模型或特征契约不符，拒绝打包。"
        }
        val pairFile = decisionAssetSource.resolve("nn_number_pair4_10.onnx")
        val pairMeta = groovy.json.JsonSlurper().parse(decisionAssetSource.resolve("nn_number_pair4_10_meta.json")) as Map<*, *>
        check(pairMeta["labels"] == listOf("4","10","other") &&
            (pairMeta["model_bytes"] as Number).toLong() == pairFile.length() &&
            pairFile.length() in 400_000L..1_000_000L &&
            pairMeta["model_sha256"] == pairFile.inputStream().use(::sha256Hex) &&
            pairMeta["encoder_sha256"] == segmentMeta["encoder_sha256"] &&
            (pairMeta["input_dimension"] as Number).toInt() == 2048 &&
            (pairMeta["control_prefix_rows"] as Number).toInt() == 4 &&
            (pairMeta["bins"] as Number).toInt() == 4 &&
            (pairMeta["threshold"] as Number).toDouble() == .995 &&
            (pairMeta["min_margin"] as Number).toDouble() == .99 &&
            (pairMeta["temperature"] as Number).toDouble() == 1.0) {
            "4/10模型、拒识类别或特征契约不符，拒绝打包。"
        }
    }
}

val generateDecisionAssetManifest = tasks.register("generateAudioDecisionAssetManifest") {
    dependsOn(verifyDecisionAssets)
    inputs.files(decisionAssetNames.map { decisionAssetSource.resolve(it) })
    val manifest = generatedDecisionAssets.map { it.file("audio_decision_assets.sha256") }
    outputs.file(manifest)
    doLast {
        val output = manifest.get().asFile
        output.parentFile.mkdirs()
        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("metadata.asset_root=app/src/main/assets")
            writer.appendLine("metadata.onnxruntime_android=1.20.0")
            writer.appendLine("metadata.sherpa_onnx_aar=1.13.7")
            for (name in decisionAssetNames) {
                val asset = decisionAssetSource.resolve(name)
                val sha = asset.inputStream().use(::sha256Hex)
                writer.appendLine("metadata.$name.path=app/src/main/assets/$name")
                writer.appendLine("metadata.$name.bytes=${asset.length()}")
                writer.appendLine("$name=$sha")
            }
        }
    }
}

val verifySpeechAssets = tasks.register("verifySpeechAssets") {
    doLast {
        check(speechAssetNames.all { decisionAssetSource.resolve(it).isFile && decisionAssetSource.resolve(it).length() > 0L }) {
            "普通识别模型、VAD、tokens或命令词表缺失，拒绝打包。"
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifySpeechAssets) }
// 回放JSON不是源码；显式输入防止换了新声音仍复用旧测试结果。
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    inputs.files(decisionAssetSource.resolve("nn_number_pair4_10.onnx"),
        decisionAssetSource.resolve("nn_number_pair4_10_meta.json"))
    inputs.files(rootProject.fileTree("_test/m6/number_acoustic_v3/out/pair4_10") {
        include("replay_*.json", "device_probe/pc.json", "device_probe/device.json", "device_probe/device_reopen.json")
    })
    inputs.files(rootProject.file("_test/m6/number_acoustic_v3/out/pair4_10/full_final2.json"))
    inputs.files(rootProject.fileTree("_test/m6/number_route_recovery/acceptance") {
        include("replay.json", "freeze.json")
    })
}
// 当前生产包校验：保住普通识别原件，确认二审资源确实未打包。
fun registerSpeechPackageCheck(taskName: String, variant: String) = tasks.register(taskName) {
    dependsOn("assemble${variant.replaceFirstChar(Char::uppercaseChar)}")
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/$variant/app-$variant.apk").get().asFile
        check(apk.isFile) { "$variant APK 未生成：$apk" }
        ZipFile(apk).use { zip ->
            val assetPaths = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("assets/") }
                .map { it.name }.toSet()
            check(assetPaths == speechAssetNames.map { "assets/$it" }.toSet()) {
                "APK 资产超出主识别白名单：${assetPaths - speechAssetNames.map { "assets/$it" }.toSet()}"
            }
            val packaged = speechAssetNames.map { "assets/$it" } +
                "lib/arm64-v8a/libsherpa-onnx-jni.so" +
                "lib/armeabi-v7a/libsherpa-onnx-jni.so" +
                "lib/arm64-v8a/libonnxruntime.so" +
                "lib/armeabi-v7a/libonnxruntime.so"
            val missing = packaged.filter { zip.getEntry(it) == null }
            check(missing.isEmpty()) { "$variant APK 缺少运行时资源：${missing.joinToString()}" }
        for (name in speechAssetNames) {
            val expected = decisionAssetSource.resolve(name).inputStream().use(::sha256Hex)
            val actual = zip.getInputStream(zip.getEntry("assets/$name")).use(::sha256Hex)
            check(actual == expected) { "$variant APK 中 $name 与已校验源文件不一致。" }
        }
        val nativeEntries = listOf(
            "lib/arm64-v8a/libsherpa-onnx-jni.so",
            "lib/armeabi-v7a/libsherpa-onnx-jni.so",
            "lib/arm64-v8a/libonnxruntime.so",
            "lib/armeabi-v7a/libonnxruntime.so",
        )
        for (path in nativeEntries) {
            check(zip.getEntry(path).size > 1_000_000L) { "APK 中运行库体积异常：$path" }
        }
        for (name in decisionAssetNames + "audio_decision_assets.sha256") {
            check(zip.getEntry("assets/$name") == null) { "已移除二审资源仍在APK：$name" }
        }
        ZipFile(file("libs/sherpa-onnx-1.13.7.aar")).use { sherpa ->
            for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
                check(zip.getEntry("lib/$abi/libonnxruntime4j_jni.so") == null) { "二审JNI桥仍在APK：$abi" }
                for (library in listOf("libonnxruntime.so", "libsherpa-onnx-jni.so", "libsherpa-onnx-c-api.so", "libsherpa-onnx-cxx-api.so")) {
                    val source = sherpa.getEntry("jni/$abi/$library") ?: error("Sherpa源库缺失：$abi/$library")
                    val target = zip.getEntry("lib/$abi/$library") ?: error("APK普通识别库缺失：$abi/$library")
                    check(sherpa.getInputStream(source).use(::sha256Hex) == zip.getInputStream(target).use(::sha256Hex)) {
                        "普通识别原生库被替换：$abi/$library"
                    }
                }
            }
        }
        }
    }
}

val verifySpeechPackage = registerSpeechPackageCheck("verifySpeechPackage", "debug")
val verifySpeechReleasePackage = registerSpeechPackageCheck("verifySpeechReleasePackage", "release")

// 兼容已有构建命令；当前实际执行普通识别/瘦身包校验。
tasks.register("verifyAudioDecisionPackage") { dependsOn(verifySpeechPackage) }

dependencies {
    // 2026-09-28：sherpa JNI 要求 VERS_1.27.1，Maven 没有同版 Android Java AAR。
    // 当前先保留 sherpa 自带的 runtime 让主语音链路稳定；二审加载失败会自动关闭开关。
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    // 离线语音识别引擎：Sherpa-ONNX JNI 与 Java API（runtime 由上方统一提供）
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))

    // 汉字转拼音：命令纠错时把「滑动/华动」这类同音字统一成拼音再比对
    implementation("com.belerweb:pinyin4j:2.5.0")

    // 单元测试：匹配逻辑回归测试（阶段一：测试基建）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
