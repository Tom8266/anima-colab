package com.tom.animacolab.colab

/**
 * 一键模式可申请的 Colab 加速卡。
 *
 * [apiName] / [variant] 直接拼进 assignments API 的查询参数：
 * `variant=GPU&accelerator=T4` 或 `variant=TPU&accelerator=V5E1`。
 * [deployMode] 会写进 kernel 环境变量 `ANIMA_ACCELERATOR`，部署脚本据此选择
 * fp16 / bf16、ComfyUI TPU 设备补丁和启动参数。
 */
enum class ColabAccelerator(
    val id: String,
    val apiName: String,
    val variant: String,
    val variantValue: Int,
    val label: String,
    val shortLabel: String,
    val deployMode: String,
    val summary: String,
    val detail: String,
) {
    T4(
        id = "t4",
        apiName = "T4",
        variant = "GPU",
        variantValue = 1,
        label = "T4 GPU",
        shortLabel = "T4",
        deployMode = "t4",
        summary = "免费版经典档 · fp16 · 1024² / 30 步约 70–90 秒",
        detail = "稳定优先，ComfyUI 用 --force-fp16 启动；显存 15GB，base 模型全量常驻。",
    ),
    TPU_V5E1(
        id = "tpu-v5e1",
        apiName = "V5E1",
        variant = "TPU",
        variantValue = 2,
        label = "TPU v5e1",
        shortLabel = "TPU",
        deployMode = "tpu",
        summary = "实验性 · bf16 · 比 T4 快约 2 倍，首图需先编译",
        detail = "ComfyUI 需打 TPU 设备补丁；首张图会先做 JIT 编译（约 1 分钟），之后约 1.1 秒/步。",
    ),
    ;

    val isTpu: Boolean get() = this == TPU_V5E1

    /** assignments 列表/响应里的 accelerator + variant 是否指向本档位。 */
    fun matches(accelerator: String?, variant: Int?): Boolean {
        val nameMatches = accelerator?.trim()?.equals(apiName, ignoreCase = true) == true
        if (!nameMatches) return false
        // variant 缺失（老响应）时只按 accelerator 判断。
        return variant == null || variant == 0 || variant == variantValue
    }

    companion object {
        fun fromId(id: String?): ColabAccelerator =
            entries.firstOrNull { it.id == id?.trim() } ?: T4

        fun fromApi(accelerator: String?, variant: Int?): ColabAccelerator? =
            entries.firstOrNull { it.matches(accelerator, variant) }
    }
}
