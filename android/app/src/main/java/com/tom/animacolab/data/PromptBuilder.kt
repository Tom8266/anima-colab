package com.tom.animacolab.data

import org.json.JSONArray
import org.json.JSONObject

/** 构造 ComfyUI API 格式的节点图（接口契约 §5） */
object PromptBuilder {

    fun buildGraph(p: GenParams): JSONObject {
        val g = JSONObject()

        g.put("1", JSONObject()
            .put("class_type", "CLIPLoader")
            .put("inputs", JSONObject()
                .put("clip_name", p.textEncoder)
                .put("type", "stable_diffusion")
                .put("device", "default")))

        g.put("2", JSONObject()
            .put("class_type", "VAELoader")
            .put("inputs", JSONObject().put("vae_name", p.vae)))

        g.put("3", JSONObject()
            .put("class_type", "CLIPTextEncode")
            .put("inputs", JSONObject()
                .put("text", p.positive)
                .put("clip", JSONArray().put("1").put(0))))

        g.put("4", JSONObject()
            .put("class_type", "CLIPTextEncode")
            .put("inputs", JSONObject()
                .put("text", p.negative)
                .put("clip", JSONArray().put("1").put(0))))

        g.put("5", JSONObject()
            .put("class_type", "UNETLoader")
            .put("inputs", JSONObject()
                .put("unet_name", p.unet)
                .put("weight_dtype", "default")))

        val modelRef = if (!p.lora.isNullOrBlank()) {
            g.put("10", JSONObject()
                .put("class_type", "LoraLoaderModelOnly")
                .put("inputs", JSONObject()
                    .put("lora_name", p.lora)
                    .put("strength_model", p.loraStrength)
                    .put("model", JSONArray().put("5").put(0))))
            JSONArray().put("10").put(0)
        } else {
            JSONArray().put("5").put(0)
        }

        g.put("6", JSONObject()
            .put("class_type", "EmptyLatentImage")
            .put("inputs", JSONObject()
                .put("width", p.width)
                .put("height", p.height)
                .put("batch_size", p.batch)))

        g.put("7", JSONObject()
            .put("class_type", "KSampler")
            .put("inputs", JSONObject()
                .put("seed", p.seed)
                .put("steps", p.steps)
                .put("cfg", p.cfg)
                .put("sampler_name", p.sampler)
                .put("scheduler", p.scheduler)
                .put("denoise", 1.0)
                .put("model", modelRef)
                .put("positive", JSONArray().put("3").put(0))
                .put("negative", JSONArray().put("4").put(0))
                .put("latent_image", JSONArray().put("6").put(0))))

        g.put("8", JSONObject()
            .put("class_type", "VAEDecode")
            .put("inputs", JSONObject()
                .put("samples", JSONArray().put("7").put(0))
                .put("vae", JSONArray().put("2").put(0))))

        g.put("9", JSONObject()
            .put("class_type", "SaveImage")
            .put("inputs", JSONObject()
                .put("filename_prefix", p.filenamePrefix)
                .put("images", JSONArray().put("8").put(0))))

        return g
    }

    /** 正负提示词拼装：按模型档位自动补质量前缀/负向预设 */
    fun composePositive(userPrompt: String, preset: ModelPreset?, autoQuality: Boolean): String {
        val prompt = userPrompt.trim()
        if (!autoQuality) return prompt
        val quality = preset?.qualityPositive?.trim().orEmpty()
        if (quality.isBlank()) return prompt
        if (prompt.isBlank()) return quality
        // 已有质量标签就不重复加
        val low = prompt.lowercase()
        val has = quality.split(',').any { part ->
            val t = part.trim().lowercase()
            t.isNotBlank() && low.contains(t)
        }
        return if (has) prompt else "$quality, $prompt"
    }

    fun sizeLabel(w: Int, h: Int): String = "${w}×${h}"

    fun seedFromText(text: String): Long? = text.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()?.coerceIn(0L, 4294967295L)

    fun randomSeed(): Long = (System.currentTimeMillis() % 4294967295L)
}
