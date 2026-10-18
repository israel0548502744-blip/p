package com.blueshield.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HomeRepairService
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HomeRepairService
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.BuildInfo

/** The four tabs of the bottom bar (the "+" in the middle opens a new photo or video). */
enum class Tab { HOME, PROJECTS, TOOLS, SETTINGS }

/** Pages opened from the side menu or from a screen, shown over the tabs. */
enum class Page { PRIVACY, FAQ, PRESETS, BRUSH, ABOUT, PRO }

/** The crown of "Pro" (not in the Material set). */
val CrownIcon: ImageVector by lazy {
    ImageVector.Builder("crown", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
        moveTo(3f, 8.5f); lineTo(7.5f, 12f); lineTo(12f, 5f); lineTo(16.5f, 12f); lineTo(21f, 8.5f)
        lineTo(19.2f, 17.5f); lineTo(4.8f, 17.5f); close()
        moveTo(4.8f, 19f); lineTo(19.2f, 19f); lineTo(19.2f, 20.6f); lineTo(4.8f, 20.6f); close()
    }.build()
}

/** "BlueShield" with the shield, and the line under it. */
@Composable
fun BrandTitle(sub: String? = tr("צנזור חכם • פרטי • מאובטח")) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BlueShield", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
            Spacer(Modifier.width(6.dp))
            ShieldBadge(24)
        }
        if (sub != null) Text(sub, color = Palette.Ink300, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The violet shield with a light rim, as next to the name in the design. */
@Composable
fun ShieldBadge(size: Int) {
    Box(contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Shield, null, tint = Palette.Shield300, modifier = Modifier.size(size.dp))
        Icon(Icons.Filled.Shield, null, tint = Palette.Shield500, modifier = Modifier.size((size * 0.78f).dp))
    }
}

/** A square button with rounded corners and a thin rim (the menu and the crown at the top). */
@Composable
private fun SquareButton(onClick: () -> Unit, label: String, tinted: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.size(46.dp).clip(shape)
            .background(if (tinted) Palette.Shield500.copy(alpha = 0.18f) else Palette.Ink850)
            .border(1.dp, if (tinted) Palette.Shield400.copy(alpha = 0.35f) else Palette.Border, shape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The bar at the top of the tabs: the crown (Pro) at the start, the name in the middle, the menu at the end. */
@Composable
fun TopBar(onMenu: () -> Unit, onPro: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        SquareButton(onPro, "Pro", tinted = true, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(CrownIcon, "Pro", tint = Palette.Shield300, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.align(Alignment.Center).padding(horizontal = 54.dp)) { BrandTitle() }
        SquareButton(onMenu, tr("תפריט"), tinted = false, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Filled.Menu, tr("תפריט"), tint = Palette.Text)
        }
    }
}

/** The bar at the top of a step or a page: its name in the middle and the way back at the side. */
@Composable
fun FlowHeader(title: String, onBack: (() -> Unit)?) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        // as in the design, the arrow sits at the left and points left (in English: the usual back arrow)
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.align(if (rtl) Alignment.CenterEnd else Alignment.CenterStart)) {
            Icon(if (rtl) Icons.AutoMirrored.Filled.ArrowForward else Icons.AutoMirrored.Filled.ArrowBack, tr("חזרה"), tint = Palette.Text)
        }
        Text(
            title, color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(horizontal = 48.dp),
        )
    }
}

/** The four steps of every photo and video: choose, process, censor, save. */
val STEP_LABELS get() = listOf(tr("בחירה"), tr("עיבוד"), tr("צנזור"), tr("שמירה"))

/**
 * The step bar: finished steps are teal with a tick, the current one is violet (with a turning ring while
 * something runs), the ones ahead are grey. [current] = 4: all done.
 */
@Composable
fun Stepper(current: Int, running: Boolean, modifier: Modifier = Modifier) {
    val labels = STEP_LABELS
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val spin by rememberInfiniteTransition(label = "step").animateFloat(
        0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    Box(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        // the lines between the circles, behind them
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            val n = labels.size
            val y = size.height / 2
            for (i in 0 until n - 1) {
                fun cx(k: Int) = ((k + 0.5f) / n * size.width).let { if (rtl) size.width - it else it }
                val a = cx(i)
                val b = cx(i + 1)
                val dir = if (b > a) 1 else -1
                drawLine(
                    if (i < current) Palette.Teal.copy(alpha = 0.7f) else Palette.Ink600,
                    Offset(a + dir * 20.dp.toPx(), y), Offset(b - dir * 20.dp.toPx(), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for ((i, label) in labels.withIndex()) {
                val done = i < current
                val now = i == current
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        when {
                            done -> Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.Teal), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Check, null, tint = Color(0xFF0B2B2A), modifier = Modifier.size(18.dp))
                            }
                            now -> {
                                Box(Modifier.size(34.dp).clip(CircleShape).background(Palette.Shield500.copy(alpha = 0.25f)))
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape).background(Glass.Accent).border(1.dp, Palette.Shield300, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) { Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White)) }
                                if (running) Canvas(Modifier.size(34.dp)) {
                                    rotate(spin) {
                                        drawCircle(
                                            Palette.Shield300, size.minDimension / 2 - 1.dp.toPx(),
                                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 5.dp.toPx()))),
                                        )
                                    }
                                }
                            }
                            else -> Box(
                                Modifier.size(28.dp).clip(CircleShape).background(Palette.Ink800).border(1.dp, Palette.Ink600, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.Check, null, tint = Palette.Ink400, modifier = Modifier.size(16.dp)) }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        label, color = if (now) Palette.Text else Palette.Ink400, fontSize = 12.5.sp,
                        fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

/** The bottom bar: home and projects, the round "+" in the middle, tools and settings. */
@Composable
fun BottomNav(tab: Tab, onTab: (Tab) -> Unit, onPlus: () -> Unit, settingsDot: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(94.dp)) {
        val shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(72.dp).clip(shape).background(Glass.Bar)
                .border(1.dp, Brush.verticalGradient(listOf(Glass.RimTop, Color.Transparent)), shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            @Composable
            fun item(t: Tab, label: String, on: ImageVector, off: ImageVector) {
                val active = t == tab
                Column(
                    Modifier.weight(1f).fillMaxHeight().clickable { onTab(t) }.padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Icon(if (active) on else off, null, tint = if (active) Palette.Shield400 else Palette.Ink300, modifier = Modifier.size(26.dp))
                        if (t == Tab.SETTINGS && settingsDot) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(Palette.Shield400))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(label, color = if (active) Palette.Shield400 else Palette.Ink300, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
            item(Tab.HOME, tr("בית"), Icons.Filled.Home, Icons.Outlined.Home)
            item(Tab.PROJECTS, tr("פרויקטים"), Icons.Filled.Folder, Icons.Outlined.Folder)
            Spacer(Modifier.weight(1f))
            item(Tab.TOOLS, tr("כלים"), Icons.Filled.HomeRepairService, Icons.Outlined.HomeRepairService)
            item(Tab.SETTINGS, tr("הגדרות"), Icons.Filled.Settings, Icons.Outlined.Settings)
        }
        // the "+": a glowing violet circle standing out of the bar
        Box(
            Modifier.align(Alignment.TopCenter).offset(y = 0.dp).size(64.dp)
                .drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Palette.Shield500.copy(alpha = 0.55f), Color.Transparent)), size.minDimension * 0.85f)
                }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFC59BFF), Palette.Shield500, Palette.Shield600)))
                .border(1.dp, Brush.verticalGradient(listOf(Color(0x88FFFFFF), Color(0x11FFFFFF))), CircleShape)
                .clickable(onClickLabel = tr("חדש"), onClick = onPlus),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, tr("חדש"), tint = Color.White, modifier = Modifier.size(32.dp)) }
    }
}

/** What the side menu can open. */
enum class DrawerItem { HOME, FILES, PROJECTS, BATCH, PRESETS, SETTINGS, PRIVACY, FAQ, CONTACT, SHARE, PROFILE }

/**
 * The side menu: the name and version, the user's card (name, e-mail, "Pro"), every place in the app, and the
 * night lake at the bottom.
 */
@Composable
fun DrawerContent(selected: DrawerItem?, name: String, email: String, onClose: () -> Unit, onItem: (DrawerItem) -> Unit) {
    Box(Modifier.fillMaxSize().background(Palette.Ink900)) {
        if (Palette.dark) Landscape(Scene.LAKE, Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.36f))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(if (rtl) Alignment.TopEnd else Alignment.TopStart)) {
                    Icon(Icons.Filled.Close, tr("סגירה"), tint = Palette.Ink200)
                }
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandTitle(sub = null)
                    Text(tr("גרסה {0}", ltr(BuildInfo.version)), color = Palette.Ink300, fontSize = 12.5.sp)
                }
            }
            // the user's card
            val card = RoundedCornerShape(18.dp)
            Row(
                Modifier.fillMaxWidth().clip(card).background(Palette.Ink850).border(1.dp, Palette.Border, card)
                    .clickable { onItem(DrawerItem.PROFILE) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(54.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Palette.Shield400, Palette.Shield700))),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(34.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name.ifBlank { tr("המשתמש שלי") }, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        ProPill()
                    }
                    Text(
                        email.ifBlank { tr("לחצו כדי להוסיף שם ומייל") }, color = Palette.Ink300, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.Ink300)
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(card).background(Palette.Ink850.copy(alpha = 0.7f)).border(1.dp, Palette.Border, card).padding(vertical = 6.dp)) {
                @Composable
                fun item(it: DrawerItem, icon: ImageVector, label: String) {
                    val on = it == selected
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(52.dp).clip(RoundedCornerShape(12.dp))
                            .background(if (on) Palette.Ink700 else Color.Transparent)
                            .clickable { onItem(it) }.padding(start = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(icon, null, tint = if (on) Palette.Shield300 else Palette.Ink200, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(label, color = if (on) Palette.Text else Palette.Ink100, fontSize = 16.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        if (on) {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.Ink200, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Shield400))
                        }
                    }
                }
                @Composable
                fun divider() = HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                item(DrawerItem.HOME, Icons.Outlined.Home, tr("בית"))
                item(DrawerItem.FILES, Icons.Outlined.Folder, tr("הקבצים שלי"))
                item(DrawerItem.PROJECTS, Icons.Outlined.SmartDisplay, tr("פרויקטים"))
                item(DrawerItem.BATCH, Icons.Outlined.Layers, tr("עריכה קבוצתית"))
                item(DrawerItem.PRESETS, Icons.Outlined.Shield, tr("תבניות שמירה"))
                divider()
                item(DrawerItem.SETTINGS, Icons.Outlined.Settings, tr("הגדרות"))
                item(DrawerItem.PRIVACY, Icons.Outlined.Lock, tr("פרטיות ואבטחה"))
                item(DrawerItem.FAQ, Icons.AutoMirrored.Outlined.HelpOutline, tr("שאלות נפוצות"))
                item(DrawerItem.CONTACT, Icons.Outlined.Email, tr("צור קשר"))
                divider()
                item(DrawerItem.SHARE, Icons.Outlined.IosShare, tr("שתף את BlueShield"))
            }
            Spacer(Modifier.height(180.dp))
        }
    }
}

/** The small "Pro" label. */
@Composable
fun ProPill() {
    Text(
        "Pro", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.Shield500).padding(horizontal = 7.dp, vertical = 1.dp),
    )
}

/** A card with a heading row (icon + title), as on the home screen. */
@Composable
fun SectionCard(title: String, icon: ImageVector?, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = Palette.Ink300, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, color = Palette.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                trailing?.invoke()
            }
            content()
        }
    }
}

/** A section heading on a page, with an optional link at the end ("הצג הכל"). */
@Composable
fun SectionHeading(title: String, link: String? = null, onLink: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (link != null && onLink != null) Text(
            link, color = Palette.Shield400, fontSize = 13.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLink).padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** The arrow of the design's "go on" buttons: it points right in every language, as drawn in the design. */
@Composable
fun DesignArrow(tint: Color, size: Int = 20) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Icon(
        Icons.AutoMirrored.Filled.ArrowForward, null, tint = tint,
        modifier = Modifier.size(size.dp).then(if (rtl) Modifier.graphicsLayer(scaleX = -1f) else Modifier),
    )
}
