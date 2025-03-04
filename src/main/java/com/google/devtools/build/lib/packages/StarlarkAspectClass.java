// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.packages;

import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;

/** {@link AspectClass} for aspects defined in Starlark. */
@Immutable
public final class StarlarkAspectClass implements AspectClass {
  private final Label extensionLabel;
  private final String exportedName;

  public StarlarkAspectClass(Label extensionLabel, String exportedName) {
    this.extensionLabel = extensionLabel;
    this.exportedName = exportedName;
  }

  public Label getExtensionLabel() {
    return extensionLabel;
  }

  public String getExportedName() {
    return exportedName;
  }

  @Override
  public String getName() {
    return extensionLabel + "%" + exportedName;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }

    if (!(o instanceof StarlarkAspectClass)) {
      return false;
    }

    StarlarkAspectClass that = (StarlarkAspectClass) o;

    return extensionLabel.equals(that.extensionLabel)
        && exportedName.equals(that.exportedName);
  }

  @Override
  public int hashCode() {
    // Inlines the implementation of Objects.hashCode to avoid generating garbage.
    return 31 * extensionLabel.hashCode() + exportedName.hashCode();
  }

  @Override
  public String toString() {
    return getName();
  }
}
