/*
 * Composition/translation orchestration adapted from AndroidX Glance 1.1.1
 * GlanceRemoteViews.kt and LazyListTranslator.kt (Copyright 2022 The Android Open
 * Source Project), licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.danila.hacustomwidgets.dashboard

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.compose.runtime.*
import androidx.compose.ui.unit.DpSize
import androidx.glance.*
import androidx.glance.Applier as GlanceApplier
import androidx.glance.appwidget.*
import androidx.glance.appwidget.lazy.EmittableLazyColumn
import androidx.glance.appwidget.lazy.EmittableLazyListItem
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The same version-pinned translator for chrome, with the real widget action identity. */
internal suspend fun composeGlanceDashboard(
    context: Context, appWidgetId: Int, size: DpSize, content: @Composable () -> Unit,
): RemoteViews = withContext(BroadcastFrameClock()) {
    val root = RemoteViewsRoot(maxDepth = 50)
    val configuration = LayoutConfiguration.create(context, appWidgetId)
    val recomposer = Recomposer(coroutineContext)
    val composition = Composition(GlanceApplier(root), recomposer)
    try {
        composition.setContent {
            CompositionLocalProvider(LocalContext provides context,
                LocalGlanceId provides AppWidgetId(appWidgetId), LocalSize provides size,
                LocalAppWidgetOptions provides android.os.Bundle()) { GlanceTheme { content() } }
        }
        launch { recomposer.runRecomposeAndApplyChanges() }
        recomposer.close(); recomposer.join()
        normalizeCompositionTree(root)
        translateComposition(context, appWidgetId, root, configuration, configuration.addLayout(root), size)
    } finally { composition.dispose() }
}

/**
 * Version-pinned bridge to the actual Glance 1.1.1 collection translator. The public
 * GlanceRemoteViews API returns a whole hierarchy, not its collection items. Translating
 * the normalized list items with the library's collection context preserves its real
 * fill-in action transport, layouts, styling and recycling. No reflection or invented
 * Compose scrolling API is used. A Glance update must re-audit this bridge.
 */
internal suspend fun composeGlanceCollection(
    context: Context, appWidgetId: Int, collectionViewId: Int, size: DpSize,
    content: @Composable () -> Unit,
): RemoteViews.RemoteCollectionItems = withContext(BroadcastFrameClock()) {
    val root = RemoteViewsRoot(maxDepth = 50)
    val configuration = LayoutConfiguration.create(context, appWidgetId)
    val recomposer = Recomposer(coroutineContext)
    val composition = Composition(GlanceApplier(root), recomposer)
    try {
        composition.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalGlanceId provides AppWidgetId(appWidgetId),
                LocalSize provides size,
                LocalAppWidgetOptions provides android.os.Bundle(),
            ) { GlanceTheme { content() } }
        }
        launch { recomposer.runRecomposeAndApplyChanges() }
        recomposer.close()
        recomposer.join()
        normalizeCompositionTree(root)
        fun lists(node: Emittable): List<EmittableLazyColumn> =
            if (node is EmittableLazyColumn) listOf(node)
            else if (node is EmittableWithChildren) node.children.flatMap(::lists) else emptyList()
        val list = lists(root).single()
        val translation = TranslationContext(context, appWidgetId,
            context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            configuration, itemPosition = -1, layoutSize = size).forLazyCollection(collectionViewId)
        RemoteViews.RemoteCollectionItems.Builder().apply {
            setHasStableIds(true)
            setViewTypeCount(TopLevelLayoutsCount)
            list.children.forEachIndexed { index, child ->
                val item = child as EmittableLazyListItem
                addItem(item.itemId, translateComposition(
                    translation.forLazyViewItem(index, 0x00100000), listOf(item), configuration.addLayout(item)))
            }
        }.build()
    } finally { composition.dispose() }
}
