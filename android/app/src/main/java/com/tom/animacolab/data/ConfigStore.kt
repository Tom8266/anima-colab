package com.tom.animacolab.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 配置与用户数据的本地持久化（SharedPreferences） */
class ConfigStore(context: Context) {

    private val sp = context.getSharedPreferences("anima_config", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("base_url", "").orEmpty()
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v.trim()).apply()

    /** 稳定的客户端 id，用于 ComfyUI WebSocket 路由 */
    val clientId: String
        get() {
            val existing = sp.getString("client_id", null)
            if (!existing.isNullOrBlank()) return existing
            val id = UUID.randomUUID().toString()
            sp.edit().putString("client_id", id).apply()
            return id
        }

    fun loadForm(): FormState {
        val d = FormState()
        return FormState(
            modelId = sp.getString("f_model", d.modelId).orEmpty().ifBlank { d.modelId },
            positive = sp.getString("f_positive", "") ?: "",
            negative = sp.getString("f_negative", "") ?: "",
            steps = sp.getInt("f_steps", d.steps),
            cfg = sp.getFloat("f_cfg", d.cfg.toFloat()).toDouble(),
            sampler = sp.getString("f_sampler", d.sampler).orEmpty().ifBlank { d.sampler },
            scheduler = sp.getString("f_scheduler", d.scheduler).orEmpty().ifBlank { d.scheduler },
            width = sp.getInt("f_width", d.width),
            height = sp.getInt("f_height", d.height),
            batch = sp.getInt("f_batch", d.batch),
            seedText = sp.getString("f_seed", "") ?: "",
            seedLocked = sp.getBoolean("f_seed_locked", false),
            autoQuality = sp.getBoolean("f_auto_quality", true),
            autoNegative = sp.getBoolean("f_auto_negative", true),
            lora = sp.getString("f_lora", null),
            loraStrength = sp.getFloat("f_lora_strength", 1.0f),
        )
    }

    fun saveForm(f: FormState) {
        sp.edit()
            .putString("f_model", f.modelId)
            .putString("f_positive", f.positive)
            .putString("f_negative", f.negative)
            .putInt("f_steps", f.steps)
            .putFloat("f_cfg", f.cfg.toFloat())
            .putString("f_sampler", f.sampler)
            .putString("f_scheduler", f.scheduler)
            .putInt("f_width", f.width)
            .putInt("f_height", f.height)
            .putInt("f_batch", f.batch)
            .putString("f_seed", f.seedText)
            .putBoolean("f_seed_locked", f.seedLocked)
            .putBoolean("f_auto_quality", f.autoQuality)
            .putBoolean("f_auto_negative", f.autoNegative)
            .putString("f_lora", f.lora)
            .putFloat("f_lora_strength", f.loraStrength)
            .apply()
    }

    // ---- 用户自定义模板 ----
    fun userTemplates(): List<PromptTemplate> {
        val raw = sp.getString("user_templates", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                PromptTemplate(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    model = o.optString("model").ifBlank { null },
                    prompt = o.optString("prompt"),
                    negative = o.optString("negative"),
                    steps = o.optInt("steps", 0).takeIf { it > 0 },
                    cfg = o.optDouble("cfg", 0.0).takeIf { it > 0.0 },
                    width = o.optInt("width", 0).takeIf { it > 0 },
                    height = o.optInt("height", 0).takeIf { it > 0 },
                    note = o.optString("note").ifBlank { null },
                    user = true,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveUserTemplate(t: PromptTemplate) {
        val list = userTemplates().toMutableList()
        val id = t.id.ifBlank { "user_${System.currentTimeMillis()}" }
        val item = t.copy(id = id, user = true)
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = item else list.add(0, item)
        persistTemplates(list)
    }

    fun deleteUserTemplate(id: String) {
        persistTemplates(userTemplates().filterNot { it.id == id })
    }

    private fun persistTemplates(list: List<PromptTemplate>) {
        val arr = JSONArray()
        list.forEach { t ->
            val o = JSONObject()
                .put("id", t.id)
                .put("name", t.name)
                .put("model", t.model ?: "")
                .put("prompt", t.prompt)
                .put("negative", t.negative)
                .put("steps", t.steps ?: 0)
                .put("cfg", t.cfg ?: 0.0)
                .put("width", t.width ?: 0)
                .put("height", t.height ?: 0)
                .put("note", t.note ?: "")
            arr.put(o)
        }
        sp.edit().putString("user_templates", arr.toString()).apply()
    }

    fun clearAll() = sp.edit().clear().apply()
}
