/*
 * Copyright (C) 2020 The Android Open Source Project
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

package androidx.room.compiler.processing.javac

import androidx.room.compiler.processing.XExecutableParameterElement
import androidx.room.compiler.processing.XMemberContainer
import androidx.room.compiler.processing.javac.kotlin.KmTypeContainer
import androidx.room.compiler.processing.javac.kotlin.KmValueParameterContainer
import androidx.room.compiler.processing.util.sanitizeAsJavaParameterName
import javax.lang.model.element.VariableElement

internal class JavacMethodParameter(
    env: JavacProcessingEnv,
    override val enclosingElement: JavacExecutableElement,
    element: VariableElement,
    kotlinMetadataFactory: () -> KmValueParameterContainer?,
    val argIndex: Int
) : JavacVariableElement(env, element), XExecutableParameterElement {
    override fun isContinuationParam() =
        enclosingElement is JavacMethodElement &&
        enclosingElement.isSuspendFunction() &&
        enclosingElement.parameters.last() == this

    override fun isReceiverParam() =
        enclosingElement is JavacMethodElement &&
        enclosingElement.isExtensionFunction() &&
        enclosingElement.parameters.first() == this

    override fun isKotlinPropertyParam() =
        enclosingElement is JavacMethodElement &&
        enclosingElement.isKotlinPropertyMethod()

    override val kotlinMetadata by lazy { kotlinMetadataFactory() }

    override val name: String
        get() = (kotlinMetadata?.name ?: super.name).sanitizeAsJavaParameterName(
            argIndex = argIndex
        )

    override val kotlinType: KmTypeContainer?
        get() = kotlinMetadata?.type

    override val hasDefaultValue: Boolean
        get() = kotlinMetadata?.hasDefault() ?: false

    override val fallbackLocationText: String
        get() = if (
            enclosingElement is JavacMethodElement &&
            enclosingElement.isSuspendFunction() &&
            this === enclosingElement.parameters.last()
        ) {
            "return type of ${enclosingElement.fallbackLocationText}"
        } else {
            "$name in ${enclosingElement.fallbackLocationText}"
        }

    override val closestMemberContainer: XMemberContainer by lazy {
        enclosingElement.enclosingElement
    }
}
