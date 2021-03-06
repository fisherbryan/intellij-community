/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.testFramework;

import com.intellij.execution.ConsoleFolding;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class FailedTestDebugLogConsoleFolding extends ConsoleFolding {
  @Override
  public boolean shouldFoldLine(@NotNull String line) {
    return line.indexOf(TestLoggerFactory.FAILED_TEST_DEBUG_OUTPUT_MARKER) != -1;
  }

  @Override
  public String getPlaceholderText(@NotNull List<String> lines) {
    return " <DEBUG log>";
  }
}
