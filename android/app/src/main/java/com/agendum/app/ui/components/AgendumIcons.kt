package com.agendum.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** 轻量矢量图标集，避免引入庞大的 material-icons-extended 体积。 */
object AgendumIcons {
    val Tasks: ImageVector by lazy {
        strokeIcon("Tasks") {
            moveTo(5f, 4f); lineTo(19f, 4f); lineTo(19f, 20f); lineTo(5f, 20f); close()
            moveTo(8f, 10f); lineTo(10f, 12f); lineTo(15f, 7f)
            moveTo(8f, 16f); lineTo(15f, 16f)
        }
    }

    val Calendar: ImageVector by lazy {
        strokeIcon("Calendar") {
            moveTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 20f); lineTo(5f, 20f); close()
            moveTo(8f, 3f); lineTo(8f, 7f)
            moveTo(16f, 3f); lineTo(16f, 7f)
            moveTo(5f, 10f); lineTo(19f, 10f)
            moveTo(9f, 14f); lineTo(10f, 14f)
            moveTo(14f, 14f); lineTo(15f, 14f)
        }
    }

    val Account: ImageVector by lazy {
        strokeIcon("Account") {
            moveTo(12f, 4f)
            arcTo(3.5f, 3.5f, 0f, true, true, 11.99f, 4f)
            moveTo(4f, 20f)
            curveTo(4f, 16.5f, 7.6f, 14.5f, 12f, 14.5f)
            curveTo(16.4f, 14.5f, 20f, 16.5f, 20f, 20f)
        }
    }

    val Add: ImageVector by lazy {
        strokeIcon("Add") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }

    val Search: ImageVector by lazy {
        strokeIcon("Search") {
            moveTo(10.5f, 4f)
            arcTo(6.5f, 6.5f, 0f, true, true, 10.49f, 4f)
            moveTo(15.2f, 15.2f); lineTo(20.5f, 20.5f)
        }
    }

    val Back: ImageVector by lazy {
        strokeIcon("Back") {
            moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f)
        }
    }

    val Next: ImageVector by lazy {
        strokeIcon("Next") {
            moveTo(9f, 5f); lineTo(16f, 12f); lineTo(9f, 19f)
        }
    }

    val ExpandMore: ImageVector by lazy {
        strokeIcon("ExpandMore") {
            moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
        }
    }

    val ExpandLess: ImageVector by lazy {
        strokeIcon("ExpandLess") {
            moveTo(6f, 15f); lineTo(12f, 9f); lineTo(18f, 15f)
        }
    }

    val UncheckedCircle: ImageVector by lazy {
        strokeIcon("UncheckedCircle") {
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
        }
    }

    val CheckedCircle: ImageVector by lazy {
        strokeIcon("CheckedCircle") {
            moveTo(12f, 3f)
            arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
            moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16.5f, 9f)
        }
    }

    val ConfirmCheck: ImageVector by lazy {
        strokeIcon("ConfirmCheck") {
            moveTo(5f, 12f); lineTo(9.5f, 16.5f); lineTo(19f, 7f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    val Sync: ImageVector by lazy {
        strokeIcon("Sync") {
            moveTo(4f, 11f)
            arcTo(8f, 8f, 0f, false, true, 18.5f, 7.5f)
            moveTo(19f, 4f); lineTo(19f, 8f); lineTo(15f, 8f)
            moveTo(20f, 13f)
            arcTo(8f, 8f, 0f, false, true, 5.5f, 16.5f)
            moveTo(5f, 20f); lineTo(5f, 16f); lineTo(9f, 16f)
        }
    }

    val QrCode: ImageVector by lazy {
        strokeIcon("QrCode") {
            moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
            moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
            moveTo(14f, 14f); lineTo(17f, 14f); lineTo(17f, 17f)
            moveTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f)
        }
    }

    private fun strokeIcon(
        name: String,
        commands: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit,
    ): ImageVector {
        return ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = commands,
        ).build()
    }
}
