package com.shuaiqiu.fuckets100

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

internal data class AnswerCategoryColor(
    val accent: Color,
    val container: Color,
    val onContainer: Color
)

@Composable
internal fun answerCategoryPalette(): Map<String, AnswerCategoryColor> {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDark) {
        mapOf(
            "read_chapter" to AnswerCategoryColor(Color(0xFFB9A8E4), Color(0xFF312B44), Color(0xFFE8DFFF)),
            "simple_expression_ufi" to AnswerCategoryColor(Color(0xFF91C5A4), Color(0xFF24382C), Color(0xFFD4F2DE)),
            "simple_expression_ufk" to AnswerCategoryColor(Color(0xFFD2B666), Color(0xFF3E3218), Color(0xFFFFE8AE)),
            "topic" to AnswerCategoryColor(Color(0xFFA8B7E0), Color(0xFF283249), Color(0xFFDDE6FF)),
            "simple_expression_ufj" to AnswerCategoryColor(Color(0xFFDCA8BD), Color(0xFF3F2A34), Color(0xFFFFD9E7))
        )
    } else {
        mapOf(
            "read_chapter" to AnswerCategoryColor(Color(0xFF574A80), Color(0xFFE9E2F6), Color(0xFF2F2648)),
            "simple_expression_ufi" to AnswerCategoryColor(Color(0xFF316746), Color(0xFFDCEFE3), Color(0xFF173622)),
            "simple_expression_ufk" to AnswerCategoryColor(Color(0xFF70571B), Color(0xFFF4E7C2), Color(0xFF3D2D08)),
            "topic" to AnswerCategoryColor(Color(0xFF425F8B), Color(0xFFE2E8F6), Color(0xFF20314F)),
            "simple_expression_ufj" to AnswerCategoryColor(Color(0xFF7C4A61), Color(0xFFF3DFE8), Color(0xFF482538))
        )
    }
}

@Composable
internal fun answerCategoryColors(): Map<String, Color> {
    return answerCategoryPalette().mapValues { it.value.accent }
}
