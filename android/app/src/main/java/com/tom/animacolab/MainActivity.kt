package com.tom.animacolab

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tom.animacolab.ui.ExploreScreen
import com.tom.animacolab.ui.GalleryScreen
import com.tom.animacolab.ui.GenerateScreen
import com.tom.animacolab.ui.ImageViewerDialog
import com.tom.animacolab.ui.SettingsScreen
import com.tom.animacolab.ui.theme.AnimaShape
import com.tom.animacolab.ui.theme.AnimaTheme
import com.tom.animacolab.ui.theme.isAnimaDark

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AnimaApp() }
    }
}

/** 底部主导航的四个目的地。 */
private enum class MainTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Generate("生成", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    Gallery("图库", Icons.Filled.PhotoLibrary, Icons.Outlined.PhotoLibrary),
    Explore("速查", Icons.Filled.Search, Icons.Outlined.Search),
    Settings("设置", Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun AnimaApp(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = MainTab.entries[tabIndex.coerceIn(0, MainTab.entries.lastIndex)]
    val snackbar = remember { SnackbarHostState() }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.onAppResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.authUrlToOpen) {
        val url = state.authUrlToOpen ?: return@LaunchedEffect
        vm.consumeAuthUrl()
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { vm.message("没有可用的浏览器打开授权页，请复制链接手动打开") }
    }

    LaunchedEffect(state.message) {
        val msg = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(msg)
        vm.consumeMessage()
    }

    // 应用内深浅色与系统状态栏图标保持一致（edge-to-edge 下必需）。
    val dark = isAnimaDark(state.themeMode)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    // 返回键先回到「生成」页；再按才退出。
    BackHandler(enabled = tabIndex != 0) { tabIndex = 0 }

    AnimaTheme(themeMode = state.themeMode, dynamicColor = state.dynamicColor) {
        val motionScheme = MaterialTheme.motionScheme
        val slideSpec = motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
        val scaleSpec = motionScheme.defaultSpatialSpec<Float>()
        val fadeSpec = motionScheme.defaultEffectsSpec<Float>()
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                SnackbarHost(snackbar) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = AnimaShape.pill,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                        actionColor = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            },
            bottomBar = {
                ShortNavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ) {
                    MainTab.entries.forEachIndexed { index, item ->
                        val selected = index == tabIndex
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = { tabIndex = index },
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label,
                                )
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        val enter = fadeIn(animationSpec = fadeSpec) +
                            slideInHorizontally(animationSpec = slideSpec) { width ->
                                if (forward) width / 7 else -width / 7
                            } +
                            scaleIn(initialScale = 0.96f, animationSpec = scaleSpec)
                        val exit = fadeOut(animationSpec = fadeSpec) +
                            slideOutHorizontally(animationSpec = slideSpec) { width ->
                                if (forward) -width / 12 else width / 12
                            } +
                            scaleOut(targetScale = 0.98f, animationSpec = scaleSpec)
                        enter togetherWith exit
                    },
                    label = "mainTabTransition",
                    modifier = Modifier.fillMaxSize(),
                ) { current ->
                    when (current) {
                        MainTab.Generate -> GenerateScreen(vm, state, onNavigate = { tabIndex = it })
                        MainTab.Gallery -> GalleryScreen(vm, state, onNavigate = { tabIndex = it })
                        MainTab.Explore -> ExploreScreen(vm, state, onBack = { tabIndex = 0 })
                        MainTab.Settings -> SettingsScreen(vm, state)
                    }
                }
            }
        }

        if (state.viewerItem != null) {
            ImageViewerDialog(vm, state)
        }
    }
}
