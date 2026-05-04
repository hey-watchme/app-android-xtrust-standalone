package com.xtrust.standalone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xtrust.standalone.ui.theme.Radius
import com.xtrust.standalone.ui.theme.SidebarBackground
import com.xtrust.standalone.ui.theme.SidebarBrandMark
import com.xtrust.standalone.ui.theme.SidebarIcon
import com.xtrust.standalone.ui.theme.SidebarIconSelected
import com.xtrust.standalone.ui.theme.SidebarSelected
import com.xtrust.standalone.ui.theme.Sizes
import com.xtrust.standalone.ui.theme.Spacing

@Composable
fun XtrustSidebar(
    primary: List<SidebarItem>,
    secondary: List<SidebarItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(Sizes.sidebarWidth)
            .background(SidebarBackground)
            .padding(vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BrandMark()

        Spacer(modifier = Modifier.height(Spacing.lg))

        primary.forEach { item ->
            SidebarIconButton(
                item = item,
                selected = selectedKey == item.key,
                onClick = { onSelect(item.key) }
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
        }

        Spacer(modifier = Modifier.weight(1f))

        secondary.forEach { item ->
            SidebarIconButton(
                item = item,
                selected = selectedKey == item.key,
                onClick = { onSelect(item.key) }
            )
            Spacer(modifier = Modifier.height(Spacing.xs))
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        modifier = Modifier
            .size(Sizes.sidebarItem)
            .clip(RoundedCornerShape(Radius.md))
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "X",
            color = SidebarBrandMark,
            fontWeight = FontWeight.Black,
            fontSize = 18.sp,
            letterSpacing = (-0.5).sp
        )
    }
}

@Composable
private fun SidebarIconButton(
    item: SidebarItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    val background = if (selected) SidebarSelected else Color.Transparent
    val tint = if (selected) SidebarIconSelected else SidebarIcon

    Box(
        modifier = Modifier
            .size(Sizes.sidebarItem)
            .clip(RoundedCornerShape(Radius.md))
            .background(background)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(Sizes.sidebarIcon)
        )
    }
}

@Composable
fun XtrustShell(
    primary: List<SidebarItem>,
    secondary: List<SidebarItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxSize(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start
    ) {
        XtrustSidebar(
            primary = primary,
            secondary = secondary,
            selectedKey = selectedKey,
            onSelect = onSelect
        )
        Box(modifier = Modifier.fillMaxSize()) {
            content()
        }
    }
}

data class SidebarItem(
    val key: String,
    val label: String,
    val icon: ImageVector
)
