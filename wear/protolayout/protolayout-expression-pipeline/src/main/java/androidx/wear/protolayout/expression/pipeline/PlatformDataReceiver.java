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

package androidx.wear.protolayout.expression.pipeline;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.expression.DynamicDataBuilders.DynamicDataValue;
import androidx.wear.protolayout.expression.PlatformDataKey;

import java.util.Map;
import java.util.Set;

/**
 * Callback for receiving a PlatformDataProvider's new data.
 */
public interface PlatformDataReceiver {
    /**
     * Called when the registered data provider is sending new values.
     */
    void onData(@NonNull Map<PlatformDataKey<?>, DynamicDataValue> newData);

    /** Called when the data provider has an invalid result. */
    void onInvalidated(@NonNull Set<PlatformDataKey<?>> keys);
}
