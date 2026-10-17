package com.pramod.videoeditor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pramod.videoeditor.ui.screens.AboutScreen
import com.pramod.videoeditor.ui.screens.AiAnalysisProgressScreen
import com.pramod.videoeditor.ui.screens.AiAutoEditSetupScreen
import com.pramod.videoeditor.ui.screens.AiProviderSettingsScreen
import com.pramod.videoeditor.ui.screens.AudioMixerScreen
import com.pramod.videoeditor.ui.screens.CaptionEditorScreen
import com.pramod.videoeditor.ui.screens.EmojiStickerScreen
import com.pramod.videoeditor.ui.screens.ExportHistoryScreen
import com.pramod.videoeditor.ui.screens.ExportProgressScreen
import com.pramod.videoeditor.ui.screens.ExportSettingsScreen
import com.pramod.videoeditor.ui.screens.GeneralSettingsScreen
import com.pramod.videoeditor.ui.screens.HomeScreen
import com.pramod.videoeditor.ui.screens.MediaPickerScreen
import com.pramod.videoeditor.ui.screens.MusicLibraryScreen
import com.pramod.videoeditor.ui.screens.MyProjectsScreen
import com.pramod.videoeditor.ui.screens.NewProjectScreen
import com.pramod.videoeditor.ui.screens.OnboardingScreen
import com.pramod.videoeditor.ui.screens.PrivacyStorageScreen
import com.pramod.videoeditor.ui.screens.ProjectRecoveryScreen
import com.pramod.videoeditor.ui.screens.SfxLibraryScreen
import com.pramod.videoeditor.ui.screens.SplashScreen
import com.pramod.videoeditor.ui.screens.TemplateDetailScreen
import com.pramod.videoeditor.ui.screens.TemplateGalleryScreen
import com.pramod.videoeditor.ui.screens.TimelineEditorScreen
import com.pramod.videoeditor.ui.screens.TypographyEditorScreen
import com.pramod.videoeditor.ui.screens.VfxLibraryScreen
import com.pramod.videoeditor.ui.screens.VideoPreviewScreen

@Composable
fun AppNavGraph() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) { SplashScreen(onDone = {
            nav.navigate(Routes.ONBOARDING) { popUpTo(Routes.SPLASH) { inclusive = true } }
        }) }
        composable(Routes.ONBOARDING) { OnboardingScreen(onDone = {
            nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
        }) }
        composable(Routes.HOME) { HomeScreen(nav) }
        composable(Routes.NEW_PROJECT) { NewProjectScreen(nav) }
        composable(Routes.MEDIA_PICKER) { MediaPickerScreen(nav) }
        composable(Routes.VIDEO_PREVIEW) { VideoPreviewScreen(nav) }
        composable(Routes.TEMPLATES) { TemplateGalleryScreen(nav) }
        composable(
            Routes.TEMPLATE_DETAIL,
            arguments = listOf(navArgument("templateId") { type = NavType.StringType }),
        ) { backStack ->
            TemplateDetailScreen(nav, backStack.arguments?.getString("templateId") ?: "")
        }
        composable(Routes.AI_SETUP) { AiAutoEditSetupScreen(nav) }
        composable(Routes.AI_PROGRESS) { AiAnalysisProgressScreen(nav) }
        composable(Routes.TIMELINE) { TimelineEditorScreen(nav) }
        composable(Routes.CAPTIONS) { CaptionEditorScreen(nav) }
        composable(Routes.TYPOGRAPHY) { TypographyEditorScreen(nav) }
        composable(Routes.EMOJI) { EmojiStickerScreen(nav) }
        composable(Routes.VFX) { VfxLibraryScreen(nav) }
        composable(Routes.SFX) { SfxLibraryScreen(nav) }
        composable(Routes.MUSIC) { MusicLibraryScreen(nav) }
        composable(Routes.MIXER) { AudioMixerScreen(nav) }
        composable(Routes.EXPORT_SETTINGS) { ExportSettingsScreen(nav) }
        composable(Routes.EXPORT_PROGRESS) { ExportProgressScreen(nav) }
        composable(Routes.EXPORT_HISTORY) { ExportHistoryScreen(nav) }
        composable(Routes.PROJECTS) { MyProjectsScreen(nav) }
        composable(Routes.RECOVERY) { ProjectRecoveryScreen(nav) }
        composable(Routes.AI_SETTINGS) { AiProviderSettingsScreen(nav) }
        composable(Routes.SETTINGS) { GeneralSettingsScreen(nav) }
        composable(Routes.PRIVACY) { PrivacyStorageScreen(nav) }
        composable(Routes.ABOUT) { AboutScreen(nav) }
    }
}
