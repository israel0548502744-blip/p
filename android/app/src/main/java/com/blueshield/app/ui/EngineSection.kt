package com.blueshield.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.engine.Accelerators

/** Which processor runs the models: automatic, CPU, GPU or the AI chip — and what the last job actually used. */
@Composable
fun EngineSection() {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Accelerators.mode(context)) }
    var report by remember { mutableStateOf(Accelerators.report(context)) }
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle("מעבד לחישוב")
            Segmented(Accelerators.MODES.map { it.id to it.label }, mode, { mode = it; Accelerators.setMode(context, it) })
            Text(Accelerators.MODES.first { it.id == mode }.hint, color = Palette.Ink300, fontSize = 12.sp, lineHeight = 17.sp)
            Text(
                "בטלפון הזה: ${Accelerators.chip}" + if (Accelerators.snapdragon) " · Snapdragon: כל האפשרויות זמינות" else "",
                color = Palette.Ink400, fontSize = 11.5.sp,
            )
            Text(
                "בפעם הראשונה עם כל אפשרות, האפליקציה בודקת כל מודל על המעבד שנבחר (כמה שניות עד דקה). מודל שלא רץ שם, " +
                    "או שנותן תוצאות שונות, נשאר על המעבד הרגיל.",
                color = Palette.Ink400, fontSize = 11.5.sp, lineHeight = 16.sp,
            )
            report?.let {
                Text("בעיבוד האחרון:", color = Color.White, fontSize = 12.5.sp)
                Text(it, color = Palette.Ink300, fontSize = 11.5.sp, lineHeight = 16.sp)
            }
            GlassOutlined(
                onClick = { Accelerators.remeasure(context); report = null },
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text("בדיקה מחדש של כל המעבדים") }
        }
    }
}
