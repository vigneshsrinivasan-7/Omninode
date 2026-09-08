package com.omninode.hub.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────────
//  OmniNode Design Palette — "Neon Edge" Dark Theme
//  Inspired by: bioluminescence, neural networks, and the iQOO brand identity
// ─────────────────────────────────────────────────────────────────────────────

// ── Backgrounds ───────────────────────────────────────────────────────────────
val Background          = Color(0xFF080B14)   // Near-black with blue undertone
val BackgroundVariant   = Color(0xFF0D1120)   // Card / surface background
val SurfaceGlass        = Color(0xFF131829)   // Glassmorphism panel
val SurfaceElevated     = Color(0xFF1A2035)   // Elevated cards

// ── Primary — Quantum Cyan ────────────────────────────────────────────────────
val Primary             = Color(0xFF00E5FF)   // Vibrant cyan — primary action
val PrimaryVariant      = Color(0xFF00B8D4)   // Deeper cyan — hover / pressed
val PrimaryDim          = Color(0xFF003D4D)   // Cyan at low opacity — backgrounds
val OnPrimary           = Color(0xFF000D10)   // Text on primary

// ── Secondary — Plasma Violet ─────────────────────────────────────────────────
val Secondary           = Color(0xFF9C27FF)   // Electric violet — secondary actions
val SecondaryVariant    = Color(0xFF7B1FA2)   // Deeper violet
val SecondaryDim        = Color(0xFF2A0040)   // Violet at low opacity
val OnSecondary         = Color(0xFFFFFFFF)

// ── Tertiary — Solar Amber ────────────────────────────────────────────────────
val Tertiary            = Color(0xFFFFAB00)   // Amber — warm alerts and automations
val TertiaryDim         = Color(0xFF3D2800)   // Amber background
val OnTertiary          = Color(0xFF1A0E00)

// ── Status Colors ─────────────────────────────────────────────────────────────
val OnlineGreen         = Color(0xFF00E676)   // Device online
val OfflineGray         = Color(0xFF546E7A)   // Device offline
val AlertRed            = Color(0xFFFF1744)   // Security / critical alert
val WarnAmber           = Color(0xFFFFAB00)   // Warning state

// ── Text ──────────────────────────────────────────────────────────────────────
val TextPrimary         = Color(0xFFF0F4FF)   // Off-white for primary text
val TextSecondary       = Color(0xFF8899BB)   // Muted secondary text
val TextDisabled        = Color(0xFF3D4A66)   // Disabled / placeholder

// ── Gradients ────────────────────────────────────────────────────────────────
val GradientCyan        = listOf(Color(0xFF00E5FF), Color(0xFF0091EA))
val GradientViolet      = listOf(Color(0xFF9C27FF), Color(0xFF5C00D4))
val GradientCyanViolet  = listOf(Color(0xFF00E5FF), Color(0xFF9C27FF))
val GradientDark        = listOf(Color(0xFF080B14), Color(0xFF131829))

// ── NPU Delegate indicator colors ─────────────────────────────────────────────
val NpuActiveColor      = Color(0xFF00E5FF)   // Hexagon NPU active
val GpuActiveColor      = Color(0xFFFFAB00)   // Adreno GPU fallback
val CpuActiveColor      = Color(0xFF78909C)   // CPU fallback
