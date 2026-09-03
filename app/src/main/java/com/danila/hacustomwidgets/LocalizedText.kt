package com.danila.hacustomwidgets

import java.util.Locale

fun isRussianUi(): Boolean = Locale.getDefault().language.equals("ru", ignoreCase = true)

fun tr(english: String, russian: String): String = if (isRussianUi()) russian else english
