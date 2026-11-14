package com.tom.animacolab.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 读取 App 内置 assets 数据（models.json / tags.json / templates.json），缺失时用内置兜底 */
class Assets(private val context: Context) {

    val presets: List<ModelPreset> by lazy { loadModels() }
    val tagCategories: List<TagCategory> by lazy { loadTags() }
    val negativePresets: List<NegativePreset> by lazy { loadNegatives() }
    val snippets: List<Snippet> by lazy { loadSnippets() }
    val templates: List<PromptTemplate> by lazy { loadTemplates() }

    fun buildTemplate(t: PromptTemplate): PromptTemplate {
        val model = t.model?.let { id -> presets.firstOrNull { it.id == id } }
        return t.copy(
            steps = t.steps ?: model?.steps,
            cfg = t.cfg ?: model?.cfg,
            width = t.width ?: (model?.sizes?.firstOrNull()?.first),
            height = t.height ?: (model?.sizes?.firstOrNull()?.second),
        )
    }

    // ---------------- loading ----------------

    private fun read(name: String): String? = runCatching {
        context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrNull()

    private fun loadModels(): List<ModelPreset> {
        val raw = read("models.json")
        if (raw != null) {
            runCatching {
                val arr = JSONObject(raw).optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parsePreset(it) } }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return FALLBACK_MODELS
    }

    private fun parsePreset(o: JSONObject): ModelPreset {
        val sizes = mutableListOf<Pair<Int, Int>>()
        o.optJSONArray("sizes")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONArray(i) ?: continue
                if (s.length() >= 2) sizes.add(s.optInt(0, 0) to s.optInt(1, 0))
            }
        }
        return ModelPreset(
            id = o.optString("id"),
            match = o.optJSONArray("match")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList(),
            display = o.optString("display", o.optString("id")),
            desc = o.optString("desc", ""),
            steps = o.optInt("steps", 30),
            cfg = o.optDouble("cfg", 4.5),
            sampler = o.optString("sampler", "er_sde"),
            scheduler = o.optString("scheduler", "simple"),
            qualityPositive = o.optString("qualityPositive", ""),
            negativePreset = o.optString("negativePreset", "generic"),
            sizes = sizes.ifEmpty { listOf(1024 to 1024) },
            recommended = o.optBoolean("recommended", false),
        )
    }

    private fun loadTags(): List<TagCategory> {
        val raw = read("tags.json")
        if (raw != null) {
            runCatching {
                val arr = JSONObject(raw).optJSONArray("categories") ?: JSONArray()
                (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseCategory(it) } }
            }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return FALLBACK_TAGS
    }

    private fun parseCategory(o: JSONObject): TagCategory {
        val tags = mutableListOf<Tag>()
        o.optJSONArray("tags")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val en = t.optString("en").ifBlank { continue }
                tags.add(Tag(en, t.optString("zh", en), t.optBoolean("artist", false)))
            }
        }
        return TagCategory(o.optString("id"), o.optString("zh"), tags)
    }

    private fun loadNegatives(): List<NegativePreset> {
        val raw = read("templates.json") ?: return FALLBACK_NEGATIVES
        runCatching {
            val arr = JSONObject(raw).optJSONArray("negativePresets") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { NegativePreset(it.optString("id"), it.optString("name"), it.optString("text")) }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return FALLBACK_NEGATIVES
    }

    private fun loadSnippets(): List<Snippet> {
        val raw = read("templates.json") ?: return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("snippets") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    Snippet(it.optString("id"), it.optString("name"), it.optString("text"), it.optString("kind", "positive"))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadTemplates(): List<PromptTemplate> {
        val raw = read("templates.json") ?: return FALLBACK_TEMPLATES
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("templates") ?: return@runCatching emptyList()
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { parseTemplate(it) } }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: FALLBACK_TEMPLATES
    }

    private fun parseTemplate(o: JSONObject): PromptTemplate {
        val size = o.optJSONArray("size")
        return PromptTemplate(
            id = o.optString("id"),
            name = o.optString("name"),
            model = o.optString("model").ifBlank { null },
            prompt = o.optString("prompt"),
            negative = o.optString("negative"),
            steps = o.optInt("steps", 0).takeIf { it > 0 },
            cfg = o.optDouble("cfg", 0.0).takeIf { it > 0.0 },
            width = if (size != null && size.length() >= 2) size.optInt(0) else null,
            height = if (size != null && size.length() >= 2) size.optInt(1) else null,
            note = o.optString("note").ifBlank { null },
            user = false,
        )
    }

    fun negativePreset(id: String?): NegativePreset? = negativePresets.firstOrNull { it.id == id }

    companion object {
        private val FALLBACK_MODELS = listOf(
            ModelPreset(
                id = "base", match = listOf("anima-base-v1.0"), display = "Anima Base v1.0",
                desc = "未精修底座：30-50 步 / CFG 4-5，需要质量标签",
                steps = 30, cfg = 4.5, sampler = "er_sde", scheduler = "simple",
                qualityPositive = "masterpiece, best quality", negativePreset = "base",
                sizes = listOf(1024 to 1024, 832 to 1216, 1216 to 832, 1080 to 2400), recommended = true,
            ),
            ModelPreset(
                id = "generic", match = emptyList(), display = "通用 / 未知模型",
                desc = "未识别模型，用保守参数",
                steps = 30, cfg = 4.5, sampler = "er_sde", scheduler = "simple",
                qualityPositive = "", negativePreset = "generic",
                sizes = listOf(1024 to 1024, 832 to 1216, 1216 to 832),
            ),
        )

        private val FALLBACK_TAGS = listOf(
            TagCategory("hair", "头发", listOf(
                Tag("long hair", "长发"), Tag("short hair", "短发"), Tag("twintails", "双马尾"),
                Tag("ponytail", "马尾"), Tag("bob cut", "波波头"), Tag("silver hair", "银发"),
            )),
            TagCategory("eyes", "眼睛 / 表情", listOf(
                Tag("blue eyes", "蓝眼"), Tag("smile", "微笑"), Tag("open mouth", "张嘴"),
                Tag("closed eyes", "闭眼"), Tag("blush", "脸红"), Tag("looking at viewer", "看镜头"),
            )),
            TagCategory("clothing", "服装", listOf(
                Tag("school uniform", "校服"), Tag("serafuku", "水手服"), Tag("white dress", "白裙"),
                Tag("hoodie", "连帽衫"), Tag("maid", "女仆"), Tag("kimono", "和服"),
            )),
            TagCategory("pose", "动作 / 姿势", listOf(
                Tag("standing", "站立"), Tag("sitting", "坐着"), Tag("walking", "走路"),
                Tag("running", "奔跑"), Tag("holding umbrella", "撑伞"), Tag("arms up", "举手"),
            )),
            TagCategory("framing", "构图 / 镜头", listOf(
                Tag("portrait", "肖像"), Tag("upper body", "上半身"), Tag("cowboy shot", "七分身"),
                Tag("full body", "全身"), Tag("close-up", "特写"), Tag("from above", "俯视"),
            )),
            TagCategory("scene", "场景", listOf(
                Tag("classroom", "教室"), Tag("city", "城市"), Tag("street", "街道"),
                Tag("forest", "森林"), Tag("beach", "海滩"), Tag("night sky", "夜空"),
            )),
            TagCategory("lighting", "光照 / 氛围", listOf(
                Tag("sunlight", "阳光"), Tag("sunset", "日落"), Tag("golden hour", "黄金时刻"),
                Tag("backlighting", "逆光"), Tag("cinematic lighting", "电影光"), Tag("bokeh", "虚化"),
            )),
            TagCategory("safety", "安全分级", listOf(
                Tag("safe", "全年龄"), Tag("sensitive", "敏感"), Tag("nsfw", "NSFW"),
            )),
        )

        private val FALLBACK_NEGATIVES = listOf(
            NegativePreset("base", "base 标准负向",
                "worst quality, low quality, blurry, jpeg artifacts, text, watermark, signature, score_1, score_2, score_3"),
            NegativePreset("aesthetic", "aesthetic 负向",
                "worst quality, low quality, blurry, jpeg artifacts, text, watermark, signature"),
            NegativePreset("generic", "通用负向",
                "worst quality, low quality, blurry, text, watermark, signature"),
        )

        private val FALLBACK_TEMPLATES = listOf(
            PromptTemplate(
                id = "single", name = "标准单人（base）", model = "base", steps = 30, cfg = 4.5,
                width = 1024, height = 1024,
                prompt = "safe, 1girl, solo, long hair, looking at viewer, upper body, classroom, sunlight",
                negative = "worst quality, low quality, blurry, text, watermark, signature",
                note = "最通用的起步模板",
            ),
            PromptTemplate(
                id = "tachie", name = "白底全身立绘", model = "base", steps = 30, cfg = 4.5,
                width = 1080, height = 2400,
                prompt = "safe, 1girl, solo, (@hiten \\(hitenkei\\):1.5)",
                negative = "",
                note = "长条画布触发立绘模式；画师 tag 自己带构图",
            ),
        )
    }
}
