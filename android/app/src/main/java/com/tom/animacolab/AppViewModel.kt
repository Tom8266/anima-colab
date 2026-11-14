package com.tom.animacolab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom.animacolab.data.ApiException
import com.tom.animacolab.data.Assets
import com.tom.animacolab.data.ComfyClient
import com.tom.animacolab.data.Config
import com.tom.animacolab.data.ConfigStore
import com.tom.animacolab.data.ConnectionState
import com.tom.animacolab.data.FormState
import com.tom.animacolab.data.GenParams
import com.tom.animacolab.data.HistoryItem
import com.tom.animacolab.data.ImageLoader
import com.tom.animacolab.data.ImageRef
import com.tom.animacolab.data.JobState
import com.tom.animacolab.data.MediaSaver
import com.tom.animacolab.data.ModelPreset
import com.tom.animacolab.data.NegativePreset
import com.tom.animacolab.data.Pairing
import com.tom.animacolab.data.PromptBuilder
import com.tom.animacolab.data.PromptTemplate
import com.tom.animacolab.data.QueueStatus
import com.tom.animacolab.data.ServerInfo
import com.tom.animacolab.data.Snippet
import com.tom.animacolab.data.Tag
import com.tom.animacolab.data.TagCategory
import com.tom.animacolab.colab.ColabSessionManager
import com.tom.animacolab.colab.ColabSessionStore
import com.tom.animacolab.colab.GoogleAuth
import com.tom.animacolab.colab.GoogleAuthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.WebSocket
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class UiState(
    val config: Config = Config(),
    val connection: ConnectionState = ConnectionState.NotConfigured,
    val info: ServerInfo? = null,
    val form: FormState = FormState(),
    val presets: List<ModelPreset> = emptyList(),
    val tagCategories: List<TagCategory> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    val templates: List<PromptTemplate> = emptyList(),
    val negativePresets: List<NegativePreset> = emptyList(),
    val job: JobState = JobState.Idle,
    val serverQueue: QueueStatus = QueueStatus(0, 0),
    val history: List<HistoryItem> = emptyList(),
    val historyLoading: Boolean = false,
    val viewerItem: HistoryItem? = null,
    val viewerRef: ImageRef? = null,
    val viewerLoading: Boolean = false,
    val savingImage: Boolean = false,
    val message: String? = null,
    val session: ColabSessionManager.State = ColabSessionManager.State.Off,
    val deployLogs: List<String> = emptyList(),
    val googleLoggedIn: Boolean = false,
    val authUrlToOpen: String? = null,
) {
    val availableModels: List<Pair<ModelPreset, String>> get() = buildAvailable(info, presets)
    val currentPreset: ModelPreset? get() = presets.firstOrNull { it.id == form.modelId }

    companion object {
        fun buildAvailable(info: ServerInfo?, presets: List<ModelPreset>): List<Pair<ModelPreset, String>> {
            if (info == null) return emptyList()
            val out = mutableListOf<Pair<ModelPreset, String>>()
            val used = mutableSetOf<String>()
            for (unet in info.unets) {
                val preset = presets.firstOrNull { it.id != "generic" && it.matches(unet) }
                if (preset != null && used.add(preset.id)) out.add(preset to unet)
            }
            if (out.isEmpty()) {
                val generic = presets.firstOrNull { it.id == "generic" }
                val unet = info.unets.firstOrNull() ?: info.defaultUnet ?: ""
                if (generic != null && unet.isNotBlank()) out.add(generic to unet)
            }
            return out
        }
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = ConfigStore(app)
    private val assets = Assets(app)
    private val client = ComfyClient()
    private val loader = ImageLoader(client)
    private val googleAuthStore = GoogleAuthStore(app)
    private val googleAuth = GoogleAuth(googleAuthStore)
    private val colabSessionStore = ColabSessionStore(app)
    private val colabSession = ColabSessionManager(app, googleAuth, colabSessionStore, viewModelScope)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var jobTracker: Job? = null
    private var ws: WebSocket? = null
    private var jobPid: String? = null
    private var jobStartedAt: Long = 0L
    private var jobCompleted = false

    init {
        val config = Config(store.baseUrl, store.token)
        val form = store.loadForm()
        _state.update { s ->
            s.copy(
                config = config,
                form = form,
                presets = assets.presets,
                tagCategories = assets.tagCategories,
                snippets = assets.snippets,
                negativePresets = assets.negativePresets,
                templates = assets.templates + store.userTemplates(),
                connection = if (config.configured) ConnectionState.Checking else ConnectionState.NotConfigured,
            )
        }
        if (config.configured) connect()

        viewModelScope.launch {
            colabSession.state.collect { st -> _state.update { it.copy(session = st) } }
        }
        viewModelScope.launch {
            colabSession.logs.collect { logs -> _state.update { it.copy(deployLogs = logs) } }
        }
        viewModelScope.launch {
            colabSession.pair.collect { pair ->
                if (pair != null) {
                    store.baseUrl = pair.baseUrl
                    store.token = pair.token
                    store.sessionManaged = true
                    _state.update {
                        it.copy(
                            config = Config(pair.baseUrl, pair.token),
                            googleLoggedIn = googleAuth.isLoggedIn(),
                        )
                    }
                    connect()
                }
            }
        }
        _state.update { it.copy(googleLoggedIn = googleAuth.isLoggedIn()) }
    }

    fun imageLoader(): ImageLoader = loader

    // ---------------- 连接 ----------------

    fun connect() {
        val config = _state.value.config
        if (!config.configured) {
            _state.update { it.copy(connection = ConnectionState.NotConfigured) }
            return
        }
        _state.update { it.copy(connection = ConnectionState.Checking) }
        viewModelScope.launch {
            try {
                val health = client.health(config.baseUrl, config.token)
                if (!health.comfy) {
                    _state.update {
                        it.copy(
                            connection = ConnectionState.Error("Colab 里的 ComfyUI 还没就绪，等部署脚本跑完再试"),
                            serverQueue = QueueStatus(health.running, health.pending),
                        )
                    }
                    return@launch
                }
                val info = runCatching { client.info(config.baseUrl, config.token) }.getOrNull()
                _state.update { s ->
                    val available = UiState.buildAvailable(info, s.presets)
                    val modelId = available.firstOrNull { it.first.id == s.form.modelId }?.first?.id
                        ?: available.firstOrNull { it.first.recommended }?.first?.id
                        ?: available.firstOrNull()?.first?.id
                        ?: s.form.modelId
                    val presetChanged = modelId != s.form.modelId
                    s.copy(
                        connection = ConnectionState.Ready(health, info),
                        info = info,
                        serverQueue = QueueStatus(health.running, health.pending),
                        form = if (presetChanged) applyPresetToForm(s.form, s.presets.firstOrNull { it.id == modelId }) else s.form,
                    )
                }
            } catch (e: ApiException) {
                _state.update { it.copy(connection = ConnectionState.Error(e.userMessage)) }
            } catch (e: Exception) {
                _state.update { it.copy(connection = ConnectionState.Error(e.message ?: "连接失败")) }
            }
        }
    }

    /** App 回到前台：连接异常/未配对时自动重连，并刷新历史 */
    fun onAppResume() {
        val s = _state.value
        if (s.config.configured && (s.connection is ConnectionState.Error || s.connection is ConnectionState.NotConfigured)) {
            connect()
        }
        if (s.config.configured && s.history.isEmpty()) {
            refreshHistory()
        }
    }

    fun pastePair(raw: String) {
        val pair = Pairing.parse(raw)
        if (pair == null) {
            message("配对信息格式不对，应为 https://...|token")
            return
        }
        store.baseUrl = pair.baseUrl
        store.token = pair.token
        store.sessionManaged = false
        _state.update { it.copy(config = Config(pair.baseUrl, pair.token)) }
        message("已保存配对信息，正在连接…")
        connect()
    }

    fun setConfig(url: String, token: String) {
        val u = url.trim().trimEnd('/')
        val t = token.trim()
        store.baseUrl = u
        store.token = t
        _state.update { it.copy(config = Config(u, t)) }
        if (u.isNotBlank() && t.isNotBlank()) {
            connect()
        } else {
            _state.update { it.copy(connection = ConnectionState.NotConfigured) }
        }
    }

    fun clearConfig() {
        store.baseUrl = ""
        store.token = ""
        store.sessionManaged = false
        _state.update { it.copy(config = Config(), connection = ConnectionState.NotConfigured, info = null) }
        message("已清除配对信息")
    }

    // ---------------- 一键 Colab 会话 ----------------

    fun beginGoogleLogin() {
        runCatching { googleAuth.buildAuthUrl() }
            .onSuccess { url -> _state.update { it.copy(authUrlToOpen = url) } }
            .onFailure { message("生成 Google 授权链接失败：${it.message}") }
    }

    fun consumeAuthUrl() = _state.update { it.copy(authUrlToOpen = null) }

    fun completeGoogleLogin(rawCode: String) {
        viewModelScope.launch {
            try {
                googleAuth.exchangeCode(rawCode)
                _state.update { it.copy(googleLoggedIn = true) }
                message("Google 登录成功，可以一键启动 Colab 了")
            } catch (e: Exception) {
                message(e.message ?: "登录失败")
            }
        }
    }

    fun logoutGoogle() {
        viewModelScope.launch {
            if (colabSession.isBusy) colabSession.cancelStart()
            if (colabSessionStore.hasPair) {
                colabSession.stop()
                clearManagedConfig()
            }
            googleAuth.logout()
            _state.update { it.copy(googleLoggedIn = false, session = ColabSessionManager.State.Off) }
            message("已退出 Google")
        }
    }

    fun startColabSession() {
        if (!googleAuth.isLoggedIn()) {
            message("先登录 Google 账号")
            return
        }
        colabSession.forgetFailure()
        colabSession.start()
        message("正在启动 Colab T4…")
    }

    fun cancelColabStart() {
        colabSession.cancelStart()
        message("已取消启动")
    }

    fun stopColabSession() {
        viewModelScope.launch {
            colabSession.stop()
            clearManagedConfig()
            message("已释放 Colab 会话")
        }
    }

    private fun clearManagedConfig() {
        if (!store.sessionManaged) return
        store.baseUrl = ""
        store.token = ""
        store.sessionManaged = false
        _state.update { it.copy(config = Config(), connection = ConnectionState.NotConfigured) }
    }

    // ---------------- 表单 ----------------

    private fun updateForm(transform: (FormState) -> FormState) {
        _state.update { it.copy(form = transform(it.form)) }
        store.saveForm(_state.value.form)
    }

    fun setPositive(text: String) = updateForm { it.copy(positive = text) }
    fun setNegative(text: String) = updateForm { it.copy(negative = text) }
    fun setSteps(v: Int) = updateForm { it.copy(steps = v.coerceIn(1, 150)) }
    fun setCfg(v: Double) = updateForm { it.copy(cfg = v.coerceIn(0.0, 30.0)) }
    fun setSampler(v: String) = updateForm { it.copy(sampler = v) }
    fun setScheduler(v: String) = updateForm { it.copy(scheduler = v) }
    fun setSize(w: Int, h: Int) = updateForm { it.copy(width = w, height = h) }
    fun setBatch(v: Int) = updateForm { it.copy(batch = v.coerceIn(1, 8)) }
    fun setSeedText(text: String) = updateForm { it.copy(seedText = text.filter { c -> c.isDigit() }) }
    fun toggleSeedLock() = updateForm { it.copy(seedLocked = !it.seedLocked) }
    fun setAutoQuality(v: Boolean) = updateForm { it.copy(autoQuality = v) }
    fun setAutoNegative(v: Boolean) = updateForm { it.copy(autoNegative = v) }
    fun setLora(name: String?, strength: Float = 1.0f) = updateForm { it.copy(lora = name, loraStrength = strength) }

    fun setModel(presetId: String) {
        val preset = _state.value.presets.firstOrNull { it.id == presetId }
        updateForm { applyPresetToForm(it, preset) }
    }

    private fun applyPresetToForm(form: FormState, preset: ModelPreset?): FormState {
        if (preset == null) return form
        val size = preset.sizes.firstOrNull()
        return form.copy(
            modelId = preset.id,
            steps = preset.steps,
            cfg = preset.cfg,
            sampler = preset.sampler,
            scheduler = preset.scheduler,
            width = size?.first ?: form.width,
            height = size?.second ?: form.height,
            autoQuality = when {
                preset.id == "aesthetic" || preset.id == "kirazuri" -> false
                preset.id == "turbo" -> false
                else -> true
            },
        )
    }

    fun appendTag(text: String, positive: Boolean) {
        val add = text.trim()
        if (add.isBlank()) return
        updateForm { f ->
            if (positive) f.copy(positive = joinPrompt(f.positive, add))
            else f.copy(negative = joinPrompt(f.negative, add))
        }
        message("已加入${if (positive) "正向" else "负向"}提示词")
    }

    fun appendSnippet(text: String, positive: Boolean) = appendTag(text, positive)

    private fun joinPrompt(current: String, add: String): String {
        val base = current.trim().trimEnd(',')
        return if (base.isBlank()) add else "$base, $add"
    }

    fun applyTemplate(template: PromptTemplate) {
        val t = assets.buildTemplate(template)
        val modelId = t.model?.let { id -> _state.value.presets.firstOrNull { it.id == id }?.id }
        val availableIds = _state.value.availableModels.map { it.first.id }
        val targetModel = when {
            modelId == null -> null
            availableIds.isEmpty() -> modelId
            modelId in availableIds -> modelId
            else -> null
        }
        updateForm { f ->
            f.copy(
                positive = t.prompt,
                negative = t.negative.ifBlank { f.negative },
                steps = t.steps ?: f.steps,
                cfg = t.cfg ?: f.cfg,
                width = t.width ?: f.width,
                height = t.height ?: f.height,
                modelId = targetModel ?: f.modelId,
                autoQuality = false,
                autoNegative = t.negative.isNotBlank(),
            )
        }
        message(if (targetModel != null) "已应用模板「${t.name}」" else "已应用模板「${t.name}」（该模板模型未部署，参数照旧）")
    }

    fun saveCurrentAsTemplate(name: String) {
        val f = _state.value.form
        val t = PromptTemplate(
            id = "user_${System.currentTimeMillis()}",
            name = name.ifBlank { "我的模板" },
            model = f.modelId,
            prompt = f.positive,
            negative = f.negative,
            steps = f.steps,
            cfg = f.cfg,
            width = f.width,
            height = f.height,
            user = true,
        )
        store.saveUserTemplate(t)
        _state.update { it.copy(templates = assets.templates + store.userTemplates()) }
        message("模板已保存")
    }

    fun deleteTemplate(id: String) {
        store.deleteUserTemplate(id)
        _state.update { it.copy(templates = assets.templates + store.userTemplates()) }
        message("模板已删除")
    }

    // ---------------- 生成 ----------------

    fun generate() {
        val s = _state.value
        val config = s.config
        if (!config.configured) {
            message("先到设置里粘贴 Colab 的配对信息")
            return
        }
        if (s.job is JobState.Submitting || s.job is JobState.Queued || s.job is JobState.Running) {
            message("还有任务在跑")
            return
        }
        val preset = s.currentPreset
        val info = s.info
        val available = s.availableModels
        val unet = available.firstOrNull { it.first.id == s.form.modelId }?.second
            ?: available.firstOrNull()?.second
            ?: info?.defaultUnet
        if (unet.isNullOrBlank()) {
            message("服务器上没有可用模型，先运行 Colab 笔记本部署 base 模型")
            return
        }
        val vae = info?.defaultVae ?: info?.vaes?.firstOrNull() ?: ""
        val textEncoder = info?.defaultTextEncoder ?: info?.textEncoders?.firstOrNull() ?: ""
        if (vae.isBlank() || textEncoder.isBlank()) {
            message("服务器缺少 VAE 或文本编码器")
            return
        }

        val positive = PromptBuilder.composePositive(s.form.positive, preset, s.form.autoQuality)
        if (positive.isBlank()) {
            message("先写点提示词")
            return
        }
        val negative = if (s.form.autoNegative) {
            assets.negativePreset(preset?.negativePreset)?.text.orEmpty()
        } else {
            s.form.negative
        }
        val fixedSeed = if (s.form.seedLocked) PromptBuilder.seedFromText(s.form.seedText) else null
        val seed = fixedSeed ?: PromptBuilder.randomSeed()
        val form = s.form.copy(seedText = seed.toString())

        val params = GenParams(
            unet = unet,
            positive = positive,
            negative = negative,
            steps = form.steps,
            cfg = form.cfg,
            sampler = form.sampler,
            scheduler = form.scheduler,
            width = form.width,
            height = form.height,
            batch = form.batch,
            seed = seed,
            lora = form.lora,
            loraStrength = form.loraStrength,
            textEncoder = textEncoder,
            vae = vae,
        )

        _state.update { it.copy(form = form, job = JobState.Submitting(System.currentTimeMillis())) }
        store.saveForm(form)

        viewModelScope.launch {
            try {
                val pid = client.submit(config.baseUrl, config.token, PromptBuilder.buildGraph(params), store.clientId)
                jobPid = pid
                jobStartedAt = System.currentTimeMillis()
                jobCompleted = false
                _state.update { it.copy(job = JobState.Queued(pid, jobStartedAt)) }
                startTracking(pid)
            } catch (e: ApiException) {
                _state.update { it.copy(job = JobState.Failed(e.userMessage)) }
            } catch (e: Exception) {
                _state.update { it.copy(job = JobState.Failed(e.message ?: "提交失败")) }
            }
        }
    }

    private fun startTracking(pid: String) {
        jobTracker?.cancel()
        jobTracker = viewModelScope.launch {
            ws?.close(1000, null)
            ws = runCatching {
                client.openSocket(
                    _state.value.config.baseUrl,
                    _state.value.config.token,
                    store.clientId,
                    onEvent = { handleSocketEvent(pid, it) },
                    onClosed = { /* 轮询兜底，不处理 */ },
                )
            }.getOrNull()

            var failures = 0
            val deadline = System.currentTimeMillis() + 15 * 60 * 1000
            while (jobPid == pid && !jobCompleted && System.currentTimeMillis() < deadline) {
                delay(2000)
                try {
                    val queue = client.queue(_state.value.config.baseUrl, _state.value.config.token)
                    _state.update { it.copy(serverQueue = queue) }
                    val item = client.historyItem(_state.value.config.baseUrl, _state.value.config.token, pid)
                    if (item != null && item.images.isNotEmpty()) {
                        completeJob(pid, item)
                        failures = 0
                        break
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= 6) {
                        _state.update { it.copy(job = JobState.Failed("和 Colab 的连接断开了：${e.message ?: ""}")) }
                        break
                    }
                }
            }
            if (!jobCompleted && jobPid == pid) {
                _state.update { it.copy(job = JobState.Failed("生成超时（15 分钟），检查 Colab 会话是否还在跑")) }
            }
            closeSocket()
        }
    }

    private fun handleSocketEvent(pid: String, obj: JSONObject) {
        if (jobPid != pid || jobCompleted) return
        when (obj.optString("type")) {
            "execution_start" -> {
                val d = obj.optJSONObject("data")
                if (d != null && d.optString("prompt_id") != pid) return
                if (_state.value.job is JobState.Queued) {
                    _state.update { it.copy(job = JobState.Running(pid, 0, _state.value.form.steps, jobStartedAt)) }
                }
            }

            "executing" -> {
                val d = obj.optJSONObject("data") ?: return
                if (d.optString("prompt_id").isNotBlank() && d.optString("prompt_id") != pid) return
                val node = if (d.isNull("node")) null else d.optString("node").ifBlank { null }
                val current = _state.value.job
                if (current is JobState.Queued) {
                    _state.update { it.copy(job = JobState.Running(pid, 0, _state.value.form.steps, jobStartedAt, nodeLabel(node))) }
                } else if (current is JobState.Running) {
                    _state.update { it.copy(job = current.copy(node = nodeLabel(node))) }
                }
            }

            "progress" -> {
                val d = obj.optJSONObject("data") ?: return
                if (d.optString("prompt_id").isNotBlank() && d.optString("prompt_id") != pid) return
                val value = d.optInt("value", 0)
                val max = d.optInt("max", _state.value.form.steps)
                val current = _state.value.job
                _state.update {
                    it.copy(job = JobState.Running(pid, value, max.coerceAtLeast(1), jobStartedAt,
                        (current as? JobState.Running)?.node ?: "采样"))
                }
            }

            "execution_success" -> {
                val d = obj.optJSONObject("data")
                if (d != null && d.optString("prompt_id").isNotBlank() && d.optString("prompt_id") != pid) return
                // 历史轮询会拿到图片；这里兜底主动拉一次
                viewModelScope.launch {
                    val item = client.historyItem(_state.value.config.baseUrl, _state.value.config.token, pid)
                    if (item != null && item.images.isNotEmpty()) completeJob(pid, item)
                }
            }

            "execution_error" -> {
                val d = obj.optJSONObject("data")
                val text = d?.optString("exception_message").orEmpty().ifBlank { d?.optString("exception_type").orEmpty() }
                _state.update { it.copy(job = JobState.Failed("执行出错：${text.ifBlank { "未知错误" }}")) }
                jobCompleted = true
                closeSocket()
            }

            "status" -> {
                val queue = obj.optJSONObject("data")?.optJSONObject("status")?.optJSONObject("exec_info")
                if (queue != null) {
                    _state.update { it.copy(serverQueue = QueueStatus(0, queue.optInt("queue_remaining", 0))) }
                }
            }
        }
    }

    private fun completeJob(pid: String, item: HistoryItem) {
        if (jobCompleted) return
        jobCompleted = true
        val elapsed = System.currentTimeMillis() - jobStartedAt
        _state.update { it.copy(job = JobState.Success(pid, item, elapsed)) }
        closeSocket()
        refreshHistory()
    }

    private fun closeSocket() {
        ws?.close(1000, null)
        ws = null
    }

    fun cancelJob() {
        val s = _state.value
        val pid = jobPid
        jobCompleted = true
        jobTracker?.cancel()
        jobTracker = null
        closeSocket()
        _state.update { it.copy(job = JobState.Idle) }
        if (pid != null) {
            viewModelScope.launch {
                runCatching { client.interrupt(s.config.baseUrl, s.config.token) }
                runCatching { client.clearQueue(s.config.baseUrl, s.config.token) }
                message("已中断")
                delay(1500)
                runCatching { refreshHistory() }
            }
        }
    }

    fun dismissJob() {
        jobCompleted = true
        jobTracker?.cancel()
        jobTracker = null
        closeSocket()
        _state.update { it.copy(job = JobState.Idle) }
    }

    fun refreshHistory() {
        val config = _state.value.config
        if (!config.configured) return
        _state.update { it.copy(historyLoading = true) }
        viewModelScope.launch {
            try {
                val items = client.history(config.baseUrl, config.token, 60)
                _state.update { it.copy(history = items, historyLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(historyLoading = false) }
                message("历史加载失败：${e.message ?: ""}")
            }
        }
    }

    // ---------------- 图片 ----------------

    fun openViewer(item: HistoryItem, ref: ImageRef? = null) {
        _state.update { it.copy(viewerItem = item, viewerRef = ref ?: item.firstImage) }
    }

    fun closeViewer() {
        _state.update { it.copy(viewerItem = null, viewerRef = null) }
    }

    fun reuseParams(item: HistoryItem) {
        val p = item.params ?: return
        val preset = _state.value.presets.firstOrNull { p.model.isNotBlank() && it.id != "generic" && it.matches(p.model) }
        updateForm {
            it.copy(
                modelId = preset?.id ?: it.modelId,
                positive = p.positive,
                negative = p.negative,
                steps = if (p.steps > 0) p.steps else it.steps,
                cfg = if (p.cfg > 0) p.cfg else it.cfg,
                sampler = p.sampler.ifBlank { it.sampler },
                scheduler = p.scheduler.ifBlank { it.scheduler },
                width = if (p.width > 0) p.width else it.width,
                height = if (p.height > 0) p.height else it.height,
                batch = if (p.batch > 0) p.batch else it.batch,
                seedText = p.seed.toString(),
                seedLocked = true,
                autoQuality = false,
                autoNegative = false,
            )
        }
        message("参数已填回生成页（种子已固定）")
    }

    suspend fun loadImageBitmap(ref: ImageRef, targetW: Int, targetH: Int): android.graphics.Bitmap? {
        val config = _state.value.config
        if (!config.configured) return null
        return loader.load(ComfyClient.viewUrl(config.baseUrl, ref), config.token, targetW, targetH)
    }

    fun saveImage(item: HistoryItem, ref: ImageRef? = null) {
        val target = ref ?: item.firstImage ?: return
        val config = _state.value.config
        _state.update { it.copy(savingImage = true) }
        viewModelScope.launch {
            try {
                val bytes = client.imageBytes(config.baseUrl, config.token, target)
                val uri = MediaSaver.savePng(getApplication(), bytes, target.filename)
                if (uri != null) message("已保存到相册（Pictures/AnimaColab）") else message("保存失败")
            } catch (e: Exception) {
                message("保存失败：${e.message ?: ""}")
            } finally {
                _state.update { it.copy(savingImage = false) }
            }
        }
    }

    fun shareImage(item: HistoryItem, ref: ImageRef? = null) {
        val target = ref ?: item.firstImage ?: return
        val config = _state.value.config
        _state.update { it.copy(savingImage = true) }
        viewModelScope.launch {
            try {
                val bytes = client.imageBytes(config.baseUrl, config.token, target)
                val uri = MediaSaver.savePng(getApplication(), bytes, target.filename)
                if (uri != null) {
                    MediaSaver.share(getApplication(), uri)
                } else {
                    message("分享失败：无法写入相册")
                }
            } catch (e: Exception) {
                message("分享失败：${e.message ?: ""}")
            } finally {
                _state.update { it.copy(savingImage = false) }
            }
        }
    }

    // ---------------- 辅助 ----------------

    fun message(text: String) = _state.update { it.copy(message = text) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun serverSummary(): String {
        val s = _state.value
        val info = s.info ?: return "未连接"
        val device = info.device ?: s.connection.let { (it as? ConnectionState.Ready)?.health?.device }.orEmpty()
        val vram = info.vramTotalGb?.let { "%.1fG".format(Locale.US, it) }.orEmpty()
        return listOf(device, vram).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "T4" }
    }

    private fun nodeLabel(node: String?): String? = when (node) {
        "1", "2" -> "加载模型"
        "3", "4" -> "文本编码"
        "5" -> "加载模型"
        "6" -> "准备画布"
        "7" -> "采样"
        "8" -> "VAE 解码"
        "9" -> "保存图片"
        "10" -> "加载 LoRA"
        else -> node?.let { "节点 $it" }
    }

    fun formatCreated(seconds: Long): String {
        if (seconds <= 0) return ""
        val date = if (seconds > 10_000_000_000L) Date(seconds) else Date(seconds * 1000)
        return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(date)
    }

    fun elapsedText(startedAt: Long): String {
        val sec = ((System.currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0)
        return if (sec < 60) "${sec}s" else "${sec / 60}m${sec % 60}s"
    }
}
