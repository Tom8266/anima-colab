package com.tom.animacolab.data

/** 连接配置：Cloudflare 隧道地址 + 鉴权 token */
data class Config(
    val baseUrl: String = "",
    val token: String = "",
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && token.isNotBlank()
}

/** 模型注册表条目（assets/models.json） */
data class ModelPreset(
    val id: String,
    val match: List<String>,
    val display: String,
    val desc: String,
    val steps: Int,
    val cfg: Double,
    val sampler: String,
    val scheduler: String,
    val qualityPositive: String,
    val negativePreset: String,
    val sizes: List<Pair<Int, Int>>,
    val recommended: Boolean = false,
) {
    fun matches(unetName: String): Boolean = match.any { it.isNotBlank() && unetName.contains(it, ignoreCase = true) }
}

data class Health(
    val ok: Boolean,
    val comfy: Boolean,
    val running: Int,
    val pending: Int,
    val device: String?,
    val vramTotalGb: Double?,
)

data class ServerInfo(
    val unets: List<String>,
    val loras: List<String>,
    val vaes: List<String>,
    val textEncoders: List<String>,
    val samplers: List<String>,
    val schedulers: List<String>,
    val defaultUnet: String?,
    val defaultVae: String?,
    val defaultTextEncoder: String?,
    val device: String?,
    val vramTotalGb: Double?,
    val queue: QueueStatus,
)

data class QueueStatus(val running: Int, val pending: Int)

data class GenParams(
    val unet: String,
    val positive: String,
    val negative: String,
    val steps: Int,
    val cfg: Double,
    val sampler: String,
    val scheduler: String,
    val width: Int,
    val height: Int,
    val batch: Int,
    val seed: Long,
    val lora: String? = null,
    val loraStrength: Float = 1.0f,
    val textEncoder: String = "",
    val vae: String = "",
    val filenamePrefix: String = "animacolab",
)

data class ImageRef(val filename: String, val subfolder: String, val type: String)

data class HistoryParams(
    val model: String = "",
    val positive: String = "",
    val negative: String = "",
    val seed: Long = 0,
    val steps: Int = 0,
    val cfg: Double = 0.0,
    val sampler: String = "",
    val scheduler: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val batch: Int = 1,
)

data class HistoryItem(
    val promptId: String,
    val images: List<ImageRef>,
    val created: Long,
    val params: HistoryParams?,
) {
    val firstImage: ImageRef? get() = images.firstOrNull()
}

data class Tag(val en: String, val zh: String, val artist: Boolean = false)

data class TagCategory(val id: String, val zh: String, val tags: List<Tag>)

data class PromptTemplate(
    val id: String,
    val name: String,
    val model: String? = null,
    val prompt: String,
    val negative: String = "",
    val steps: Int? = null,
    val cfg: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val note: String? = null,
    val user: Boolean = false,
)

data class Snippet(val id: String, val name: String, val text: String, val kind: String)

data class NegativePreset(val id: String, val name: String, val text: String)

sealed interface ConnectionState {
    data object NotConfigured : ConnectionState
    data object Checking : ConnectionState
    data class Ready(val health: Health, val info: ServerInfo?) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

sealed interface JobState {
    data object Idle : JobState
    data class Submitting(val startedAt: Long) : JobState
    data class Queued(val promptId: String, val startedAt: Long) : JobState
    data class Running(
        val promptId: String,
        val step: Int,
        val max: Int,
        val startedAt: Long,
        val node: String? = null,
    ) : JobState

    data class Success(val promptId: String, val item: HistoryItem, val elapsedMs: Long) : JobState
    data class Failed(val message: String) : JobState
}

/** 生成页表单的全部可编辑状态 */
data class FormState(
    val modelId: String = "base",
    val positive: String = "",
    val negative: String = "",
    val steps: Int = 30,
    val cfg: Double = 4.5,
    val sampler: String = "er_sde",
    val scheduler: String = "simple",
    val width: Int = 1024,
    val height: Int = 1024,
    val batch: Int = 1,
    val seedText: String = "",
    val seedLocked: Boolean = false,
    val autoQuality: Boolean = true,
    val autoNegative: Boolean = true,
    val lora: String? = null,
    val loraStrength: Float = 1.0f,
)
