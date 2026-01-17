package com.expedition.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val ExpeditionShapes = Shapes(
    // Extra small - for small chips, badges
    extraSmall = RoundedCornerShape(4.dp),
    
    // Small - for small buttons, text fields
    small = RoundedCornerShape(8.dp),
    
    // Medium - for cards, dialogs
    medium = RoundedCornerShape(16.dp),
    
    // Large - for bottom sheets, large cards
    large = RoundedCornerShape(24.dp),
    
    // Extra large - for full-screen dialogs
    extraLarge = RoundedCornerShape(32.dp)
)
