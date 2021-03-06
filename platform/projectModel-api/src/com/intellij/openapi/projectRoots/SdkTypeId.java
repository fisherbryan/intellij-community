/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.projectRoots;

import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author yole
 */
public interface SdkTypeId {
  @NotNull
  String getName();

  @Nullable
  String getVersionString(@NotNull Sdk sdk);

  void saveAdditionalData(@NotNull SdkAdditionalData additionalData, @NotNull Element additional);

  @Nullable
  SdkAdditionalData loadAdditionalData(@NotNull Sdk currentSdk, Element additional);

  /**
   * An SDK can be located on a local machine or on a remote or virtual machine. In the latter case this method returns false.
   */
  default boolean isLocalSdk(@NotNull Sdk sdk) {
    return true;
  }
}
