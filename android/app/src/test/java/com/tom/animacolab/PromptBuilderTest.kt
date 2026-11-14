package com.tom.animacolab

import com.tom.animacolab.data.GenParams
import com.tom.animacolab.data.ModelPreset
import com.tom.animacolab.data.PromptBuilder
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {

    private fun params(lora: String? = null) = GenParams(
        unet = "anima-base-v1.0.safetensors",
        positive = "safe, 1girl, solo",
        negative = "worst quality",
        steps = 30,
        cfg = 4.5,
        sampler = "er_sde",
        scheduler = "simple",
        width = 1024,
        height = 1024,
        batch = 1,
        seed = 12345L,
        lora = lora,
        textEncoder = "qwen_3_06b_base.safetensors",
        vae = "qwen_image_vae.safetensors",
    )

    private fun preset(quality: String) = ModelPreset(
        id = "base",
        match = listOf("anima-base"),
        display = "base",
        desc = "",
        steps = 30,
        cfg = 4.5,
        sampler = "er_sde",
        scheduler = "simple",
        qualityPositive = quality,
        negativePreset = "base",
        sizes = listOf(1024 to 1024),
        recommended = true,
    )

    @Test
    fun graphContainsContractNodes() {
        val g = PromptBuilder.buildGraph(params())
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9").forEach { id ->
            assertTrue("缺少节点 $id", g.has(id))
        }
        assertFalse("无 LoRA 时不应有节点 10", g.has("10"))
        assertEquals("CLIPLoader", g.getJSONObject("1").getString("class_type"))
        assertEquals("UNETLoader", g.getJSONObject("5").getString("class_type"))
        assertEquals("VAELoader", g.getJSONObject("2").getString("class_type"))
        assertEquals("KSampler", g.getJSONObject("7").getString("class_type"))
        assertEquals("SaveImage", g.getJSONObject("9").getString("class_type"))

        val sampler = g.getJSONObject("7").getJSONObject("inputs")
        assertEquals(30, sampler.getInt("steps"))
        assertEquals(4.5, sampler.getDouble("cfg"), 0.0001)
        assertEquals("er_sde", sampler.getString("sampler_name"))
        assertEquals("simple", sampler.getString("scheduler"))
        assertEquals(12345L, sampler.getLong("seed"))
        assertEquals(1.0, sampler.getDouble("denoise"), 0.0001)
        assertEquals("anima-base-v1.0.safetensors", g.getJSONObject("5").getJSONObject("inputs").getString("unet_name"))
        assertEquals("qwen_image_vae.safetensors", g.getJSONObject("2").getJSONObject("inputs").getString("vae_name"))
        assertEquals("safe, 1girl, solo", g.getJSONObject("3").getJSONObject("inputs").getString("text"))
        assertEquals("worst quality", g.getJSONObject("4").getJSONObject("inputs").getString("text"))
        assertEquals(1024, g.getJSONObject("6").getJSONObject("inputs").getInt("width"))
    }

    @Test
    fun loraAddsModelOnlyNode() {
        val g = PromptBuilder.buildGraph(params(lora = "anima-turbo-lora-v0.2.safetensors"))
        assertTrue(g.has("10"))
        assertEquals("LoraLoaderModelOnly", g.getJSONObject("10").getString("class_type"))
        val ref = g.getJSONObject("7").getJSONObject("inputs").get("model") as JSONArray
        assertEquals("10", ref.getString(0))
        assertEquals(0, ref.getInt(1))
        // positive/negative 仍指向文本编码节点
        assertEquals("3", (g.getJSONObject("7").getJSONObject("inputs").get("positive") as JSONArray).getString(0))
    }

    @Test
    fun seedParsingIsClamped() {
        assertEquals(123L, PromptBuilder.seedFromText("123"))
        assertEquals(0L, PromptBuilder.seedFromText("0"))
        assertNull(PromptBuilder.seedFromText(""))
        assertNull(PromptBuilder.seedFromText("abc"))
        assertEquals(4294967295L, PromptBuilder.seedFromText("99999999999"))
    }

    @Test
    fun composePositiveAddsQualityOnce() {
        val base = preset("masterpiece, best quality")
        assertEquals(
            "masterpiece, best quality, safe, 1girl",
            PromptBuilder.composePositive("safe, 1girl", base, autoQuality = true),
        )
        assertEquals(
            "masterpiece, best quality, safe, 1girl",
            PromptBuilder.composePositive("masterpiece, best quality, safe, 1girl", base, autoQuality = true),
        )
        assertEquals(
            "masterpiece, best quality",
            PromptBuilder.composePositive("", base, autoQuality = true),
        )
        assertEquals(
            "safe, 1girl",
            PromptBuilder.composePositive("safe, 1girl", base, autoQuality = false),
        )
        assertEquals(
            "safe, 1girl",
            PromptBuilder.composePositive("safe, 1girl", preset(""), autoQuality = true),
        )
    }

    @Test
    fun sizeLabelUsesMultiplicationSign() {
        assertEquals("1024×1024", PromptBuilder.sizeLabel(1024, 1024))
    }
}
