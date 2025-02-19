/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material3

/**
 * Represents a Locale for the calendar. This locale will be used when formatting dates, determining
 * the input format, and more.
 *
 * Note: For JVM based platforms, this would be equivalent to [java.util.Locale].
 */
@ExperimentalMaterial3Api
expect class CalendarLocale

/**
 * Returns the default [CalendarLocale].
 *
 * Note: For JVM based platforms, this would be equivalent to [java.util.Locale.getDefault].
 */
@OptIn(ExperimentalMaterial3Api::class)
internal expect fun defaultLocale(): CalendarLocale

/**
 * Returns a string representation of an integer for the current Locale.
 *
 * @param minDigits sets the minimum number of digits allowed in the integer portion of a number.
 * If the minDigits value is greater than the [maxDigits] value, then [maxDigits] will also be set
 * to this value.
 *
 *
 */
internal expect fun Int.toLocalString(
    minDigits: Int = 1,
    // Removed other arguments comparing with upstream because they aren't used
): String
