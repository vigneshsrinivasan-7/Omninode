package com.omninode.hub.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.omninode.hub.ui.screen.AgentScreen
import com.omninode.hub.ui.screen.DashboardScreen
import com.omninode.hub.ui.screen.DevicesScreen
import com.omninode.hub.ui.screen.SettingsScreen
import com.omninode.hub.ui.screen.VisionScreen

/**
 * OmniNode Navigation Routes
 */
sealed class Screen(val route: String) {
    data object Dashboard : Screen("dashboard")
    data object Devices   : Screen("devices")
    data object Agent     : Screen("agent")
    data object Vision    : Screen("vision")
    data object Settings  : Screen("settings")
}

/**
 * OmniNodeNavHost — Compose Navigation graph for the entire application.
 *
 * Screens:
 *  • Dashboard — Real-time smart home status, quick device controls, automations
 *  • Devices   — Full device list organized by room, Matter commissioning
 *  • Agent     — Gemma-4-2B conversational AI interface for natural language control
 *  • Vision    — FastVLM live camera scene analysis and autonomous automation
 *  • Settings  — HA connection, NPU configuration, Office Kit bridge
 *
 * Transitions: Slide + fade for premium feel, matching the iQOO Origin OS aesthetic.
 */
@Composable
fun OmniNodeNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Dashboard.route,
) {
    NavHost(
        navController   = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(tween(200)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                tween(250)
            )
        },
        exitTransition = {
            fadeOut(tween(150)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.Start,
                tween(200)
            )
        },
        popEnterTransition = {
            fadeIn(tween(200)) + slideIntoContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(250)
            )
        },
        popExitTransition = {
            fadeOut(tween(150)) + slideOutOfContainer(
                AnimatedContentTransitionScope.SlideDirection.End,
                tween(200)
            )
        },
    ) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(navController = navController)
        }
        composable(Screen.Devices.route) {
            DevicesScreen(navController = navController)
        }
        composable(Screen.Agent.route) {
            AgentScreen(navController = navController)
        }
        composable(Screen.Vision.route) {
            VisionScreen(navController = navController)
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
    }
}
