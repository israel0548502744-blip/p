package com.blueshield.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueshield.app.BuildInfo

/** The four tabs of the bottom bar. */
enum class Tab { HOME, ACTIVITY, ABOUT, SETTINGS }

/** Pages opened from the side menu or from a screen, shown over the tabs. */
enum class Page { PRIVACY, FAQ, PRESETS, BRUSH }

/** The bar at the top: the name in the middle, the menu button at the side (a back arrow when a page is open). */
@Composable
fun TopBar(onMenu: () -> Unit, onBack: (() -> Unit)?) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("חזרה"), tint = Palette.Text)
            }
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("BlueShield", color = Palette.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.Shield, null, tint = Palette.Shield400, modifier = Modifier.size(24.dp))
            }
            Text(tr("צנזור חכם • פרטי • על המכשיר שלך"), color = Palette.Ink400, fontSize = 12.sp)
        }
        Box(
            Modifier.align(Alignment.CenterEnd).size(46.dp).clip(CircleShape).background(Palette.Ink800)
                .border(1.dp, Palette.Border, CircleShape).clickable(onClick = onMenu),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Menu, tr("תפריט"), tint = Palette.Text) }
    }
}

/** The bottom bar: home, activity (recent results), about, settings. */
@Composable
fun BottomNav(tab: Tab, onTab: (Tab) -> Unit, settingsDot: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().background(Glass.Bar).border(1.dp, Palette.Border, RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
            .padding(vertical = 6.dp),
    ) {
        val items = listOf(
            Triple(Tab.HOME, tr("בית"), Icons.Filled.Home to Icons.Outlined.Home),
            Triple(Tab.ACTIVITY, tr("פעילות"), Icons.Filled.Folder to Icons.Outlined.Folder),
            Triple(Tab.ABOUT, tr("אודות"), Icons.Filled.Info to Icons.Outlined.Info),
            Triple(Tab.SETTINGS, tr("הגדרות"), Icons.Filled.Settings to Icons.Outlined.Settings),
        )
        for ((t, label, icons) in items) {
            val on = t == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onTab(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(if (on) icons.first else icons.second, null, tint = if (on) Palette.Shield400 else Palette.Ink400, modifier = Modifier.size(26.dp))
                    if (t == Tab.SETTINGS && settingsDot) Box(Modifier.align(Alignment.TopEnd).size(7.dp).clip(CircleShape).background(Palette.Shield400))
                }
                Text(label, color = if (on) Palette.Shield400 else Palette.Ink400, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}

/** What the side menu can open. */
enum class DrawerItem { HOME, FILES, BATCH, PRESETS, SETTINGS, PRIVACY, FAQ, CONTACT, SHARE }

/** The side menu: the app's name and version, then every place in the app. */
@Composable
fun DrawerContent(selected: DrawerItem?, onItem: (DrawerItem) -> Unit) {
    Column(
        Modifier.fillMaxHeight().fillMaxWidth().background(Palette.Ink900)
            .verticalScroll(rememberScrollState()).padding(vertical = 18.dp),
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Palette.Shield400, Palette.Shield600))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Person, null, tint = Color.White, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("BlueShield", color = Palette.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Outlined.Shield, null, tint = Palette.Shield400, modifier = Modifier.size(18.dp))
                }
                Text(tr("גרסה {0}", ltr(BuildInfo.version)), color = Palette.Ink400, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = Palette.Border)
        Spacer(Modifier.height(8.dp))
        @Composable
        fun item(it: DrawerItem, icon: ImageVector, label: String) {
            val on = it == selected
            Row(
                Modifier.fillMaxWidth().height(52.dp).background(if (on) Palette.Shield500.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onItem(it) }.padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = if (on) Palette.Shield400 else Palette.Ink300, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(16.dp))
                Text(label, color = if (on) Palette.Text else Palette.Ink200, fontSize = 16.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f))
                if (on) Box(Modifier.width(4.dp).height(28.dp).clip(RoundedCornerShape(2.dp)).background(Palette.Shield400))
            }
        }
        item(DrawerItem.HOME, Icons.Filled.Home, tr("בית"))
        item(DrawerItem.FILES, Icons.Filled.Folder, tr("הקבצים שלי"))
        item(DrawerItem.BATCH, Icons.Filled.Layers, tr("עריכה קבוצתית"))
        item(DrawerItem.PRESETS, Icons.Outlined.Shield, tr("תבניות שמירה"))
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        item(DrawerItem.SETTINGS, Icons.Filled.Settings, tr("הגדרות"))
        item(DrawerItem.PRIVACY, Icons.Filled.Lock, tr("פרטיות ואבטחה"))
        item(DrawerItem.FAQ, Icons.AutoMirrored.Filled.HelpOutline, tr("שאלות נפוצות"))
        item(DrawerItem.CONTACT, Icons.Filled.Email, tr("יצירת קשר"))
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Palette.Border, modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))
        item(DrawerItem.SHARE, Icons.Filled.Share, tr("שתף את BlueShield"))
    }
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
