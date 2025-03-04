// Copyright 2016 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.sandbox;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY;
import static com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK;

import com.google.auto.value.AutoValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.common.hash.HashingOutputStream;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Spawn;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput;
import com.google.devtools.build.lib.actions.cache.VirtualActionInput.EmptyActionInput;
import com.google.devtools.build.lib.analysis.test.TestConfiguration;
import com.google.devtools.build.lib.cmdline.LabelConstants;
import com.google.devtools.build.lib.vfs.Dirent;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.FileSystemUtils.MoveResult;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Helper methods that are shared by the different sandboxing strategies.
 *
 * <p>All sandboxed strategies within a build should share the same instance of this object.
 */
public final class SandboxHelpers {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  private static final AtomicBoolean warnedAboutMovesBeingCopies = new AtomicBoolean(false);

  /**
   * Writes a virtual input file so that the final file is always consistent to all readers.
   *
   * <p>This function exists to aid dynamic scheduling. Param files are inputs, so they need to be
   * written without holding the output lock. When we have competing unsandboxed spawn runners (like
   * persistent workers), it's possible for them to clash in these writes, either encountering
   * missing file errors or encountering incomplete data. But given that we can assume both spawn
   * runners will write the same contents, we can write those as temporary files and then perform a
   * rename, which has atomic semantics on Unix, and thus keep all readers always seeing consistent
   * contents.
   *
   * @param input the virtual input file to write
   * @param outputPath final path where the virtual input file ought to live
   * @param uniqueSuffix a filename extension that is different between the local spawn runners and
   *     the remote ones
   * @return digest of written virtual input
   * @throws IOException if we fail to write the virtual input file
   */
  // TODO(b/150963503): We are using atomic file system moves for synchronization... but Bazel
  // should not be able to reach this state. Which means we should probably be doing some other
  // form of synchronization in-process before touching the file system.
  public static byte[] atomicallyWriteVirtualInput(
      VirtualActionInput input, Path outputPath, String uniqueSuffix) throws IOException {
    Path tmpPath = outputPath.getFileSystem().getPath(outputPath.getPathString() + uniqueSuffix);
    tmpPath.getParentDirectory().createDirectoryAndParents();
    try {
      byte[] digest = writeVirtualInputTo(input, tmpPath);
      // We expect the following to replace the params file atomically in case we are using
      // the dynamic scheduler and we are racing the remote strategy writing this same file.
      tmpPath.renameTo(outputPath);
      tmpPath = null; // Avoid unnecessary deletion attempt.
      return digest;
    } finally {
      try {
        if (tmpPath != null) {
          // Make sure we don't leave temp files behind if we are interrupted.
          tmpPath.delete();
        }
      } catch (IOException e) {
        // Ignore.
      }
    }
  }

  /**
   * Moves all given outputs from a root to another.
   *
   * <p>This is a support function to help with the implementation of {@link
   * SandboxfsSandboxedSpawn#copyOutputs(Path)}.
   *
   * @param outputs outputs to move as relative paths to a root
   * @param sourceRoot source directory from which to resolve outputs
   * @param targetRoot target directory to which to move the resolved outputs from the source
   * @throws IOException if any of the moves fails
   */
  public static void moveOutputs(SandboxOutputs outputs, Path sourceRoot, Path targetRoot)
      throws IOException {
    for (PathFragment output : Iterables.concat(outputs.files(), outputs.dirs())) {
      Path source = sourceRoot.getRelative(output);
      Path target = targetRoot.getRelative(output);
      if (source.isFile() || source.isSymbolicLink()) {
        // Ensure the target directory exists in the target. The directories for the action outputs
        // have already been created, but the spawn outputs may be different from the overall action
        // outputs. This is the case for test actions.
        target.getParentDirectory().createDirectoryAndParents();
        if (FileSystemUtils.moveFile(source, target).equals(MoveResult.FILE_COPIED)) {
          if (warnedAboutMovesBeingCopies.compareAndSet(false, true)) {
            logger.atWarning().log(
                "Moving files out of the sandbox (e.g. from %s to %s"
                    + ") had to be done with a file copy, which is detrimental to performance; are "
                    + "the two trees in different file systems?",
                source, target);
          }
        }
      } else if (source.isDirectory()) {
        try {
          source.renameTo(target);
        } catch (IOException e) {
          // Failed to move directory directly, thus move it recursively.
          target.createDirectory();
          FileSystemUtils.moveTreesBelow(source, target);
        }
      } else if (!source.exists()) {
        // This will show up as an error later
      } else {
        logger.atWarning().log(
            "Sandbox file %s for output %s is neither file nor symlink nor directory.",
            source, target);
      }
    }
  }

  /**
   * Cleans the existing sandbox at {@code root} to match the {@code inputs}, updating {@code
   * inputsToCreate} and {@code dirsToCreate} to not contain existing inputs and dir. Existing
   * directories or files that are either not needed {@code inputs} or doesn't have the right
   * content or symlink destination are removed.
   */
  public static void cleanExisting(
      Path root,
      SandboxInputs inputs,
      Set<PathFragment> inputsToCreate,
      Set<PathFragment> dirsToCreate,
      Path workDir)
      throws IOException {
    // To avoid excessive scanning of dirsToCreate for prefix dirs, we prepopulate this set of
    // prefixes.
    Set<PathFragment> prefixDirs = new HashSet<>();
    for (PathFragment dir : dirsToCreate) {
      PathFragment parent = dir.getParentDirectory();
      while (parent != null && !prefixDirs.contains(parent)) {
        prefixDirs.add(parent);
        parent = parent.getParentDirectory();
      }
    }
    cleanRecursively(root, inputs, inputsToCreate, dirsToCreate, workDir, prefixDirs);
  }

  /**
   * Deletes unnecessary files/directories and updates the sets if something on disk is already
   * correct and doesn't need any changes.
   */
  private static void cleanRecursively(
      Path root,
      SandboxInputs inputs,
      Set<PathFragment> inputsToCreate,
      Set<PathFragment> dirsToCreate,
      Path workDir,
      Set<PathFragment> prefixDirs)
      throws IOException {
    Path execroot = workDir.getParentDirectory();
    for (Dirent dirent : root.readdir(Symlinks.NOFOLLOW)) {
      Path absPath = root.getChild(dirent.getName());
      PathFragment pathRelativeToWorkDir;
      if (absPath.startsWith(workDir)) {
        // path is under workDir, i.e. execroot/<workspace name>. Simply get the relative path.
        pathRelativeToWorkDir = absPath.relativeTo(workDir);
      } else {
        // path is not under workDir, which means it belongs to one of external repositories
        // symlinked directly under execroot. Get the relative path based on there and prepend it
        // with the designated prefix, '../', so that it's still a valid relative path to workDir.
        pathRelativeToWorkDir =
            LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX.getRelative(
                absPath.relativeTo(execroot));
      }
      Optional<PathFragment> destination =
          getExpectedSymlinkDestination(pathRelativeToWorkDir, inputs);
      if (destination.isPresent()) {
        if (SYMLINK.equals(dirent.getType())
            && absPath.readSymbolicLink().equals(destination.get())) {
          inputsToCreate.remove(pathRelativeToWorkDir);
        } else {
          absPath.delete();
        }
      } else if (DIRECTORY.equals(dirent.getType())) {
        if (dirsToCreate.contains(pathRelativeToWorkDir)
            || prefixDirs.contains(pathRelativeToWorkDir)) {
          cleanRecursively(absPath, inputs, inputsToCreate, dirsToCreate, workDir, prefixDirs);
          dirsToCreate.remove(pathRelativeToWorkDir);
        } else {
          absPath.deleteTree();
        }
      } else if (!inputsToCreate.contains(pathRelativeToWorkDir)) {
        absPath.delete();
      }
    }
  }

  /**
   * Returns what the destination of the symlink {@code file} should be, according to {@code
   * inputs}.
   */
  static Optional<PathFragment> getExpectedSymlinkDestination(
      PathFragment fragment, SandboxInputs inputs) {
    RootedPath file = inputs.getFiles().get(fragment);
    if (file != null) {
      return Optional.of(file.asPath().asFragment());
    }
    return Optional.ofNullable(inputs.getSymlinks().get(fragment));
  }

  /** Populates the provided sets with the inputs and directories that need to be created. */
  public static void populateInputsAndDirsToCreate(
      Set<PathFragment> writableDirs,
      Set<PathFragment> inputsToCreate,
      LinkedHashSet<PathFragment> dirsToCreate,
      Iterable<PathFragment> inputFiles,
      ImmutableSet<PathFragment> outputFiles,
      ImmutableSet<PathFragment> outputDirs) {
    // Add all worker files, input files, and the parent directories.
    for (PathFragment input : inputFiles) {
      inputsToCreate.add(input);
      dirsToCreate.add(input.getParentDirectory());
    }

    // And all parent directories of output files. Note that we don't add the files themselves --
    // any pre-existing files that have the same path as an output should get deleted.
    for (PathFragment file : outputFiles) {
      dirsToCreate.add(file.getParentDirectory());
    }

    // Add all output directories.
    dirsToCreate.addAll(outputDirs);

    // Add some directories that should be writable, and thus exist.
    dirsToCreate.addAll(writableDirs);
  }

  /**
   * Creates directory and all ancestors for it at a given path.
   *
   * <p>This method uses (and updates) the set of already known directories in order to minimize the
   * I/O involved with creating directories. For example a path of {@code 1/2/3/4} created after
   * {@code 1/2/3/5} only calls for creating {@code 1/2/3/5}. We can use the set of known
   * directories to discover that {@code 1/2/3} already exists instead of deferring to the
   * filesystem for it.
   */
  public static void createDirectoryAndParentsInSandboxRoot(
      Path path, Set<Path> knownDirectories, Path sandboxExecRoot) throws IOException {
    if (knownDirectories.contains(path)) {
      return;
    }
    createDirectoryAndParentsInSandboxRoot(
        checkNotNull(
            path.getParentDirectory(),
            "Path %s is not under/siblings of sandboxExecRoot: %s",
            path,
            sandboxExecRoot),
        knownDirectories,
        sandboxExecRoot);
    path.createDirectory();
    knownDirectories.add(path);
  }

  /**
   * Creates all directories needed for the sandbox.
   *
   * <p>No input can be a child of another input, because otherwise we might try to create a symlink
   * below another symlink we created earlier - which means we'd actually end up writing somewhere
   * in the workspace.
   *
   * <p>If all inputs were regular files, this situation could naturally not happen - but
   * unfortunately, we might get the occasional action that has directories in its inputs.
   *
   * <p>Creating all parent directories first ensures that we can safely create symlinks to
   * directories, too, because we'll get an IOException with EEXIST if inputs happen to be nested
   * once we start creating the symlinks for all inputs.
   *
   * @param strict If true, absolute directories or directories with multiple up-level references
   *     are disallowed, for stricter sandboxing.
   */
  public static void createDirectories(
      Iterable<PathFragment> dirsToCreate, Path dir, boolean strict) throws IOException {
    Set<Path> knownDirectories = new HashSet<>();
    // Add sandboxExecRoot and it's parent -- all paths must fall under the parent of
    // sandboxExecRoot and we know that sandboxExecRoot exists. This stops the recursion in
    // createDirectoryAndParentsInSandboxRoot.
    knownDirectories.add(dir);
    knownDirectories.add(dir.getParentDirectory());

    for (PathFragment path : dirsToCreate) {
      if (strict) {
        Preconditions.checkArgument(!path.isAbsolute(), path);
        if (path.containsUplevelReferences() && path.isMultiSegment()) {
          // Allow a single up-level reference to allow inputs from the siblings of the main
          // repository in the sandbox execution root, but forbid multiple up-level references.
          // PathFragment is normalized, so up-level references are guaranteed to be at the
          // beginning.
          Preconditions.checkArgument(
              !PathFragment.containsUplevelReferences(path.getSegment(1)),
              "%s escapes the sandbox exec root.",
              path);
        }
      }

      createDirectoryAndParentsInSandboxRoot(dir.getRelative(path), knownDirectories, dir);
    }
  }

  /** Wrapper class for the inputs of a sandbox. */
  public static final class SandboxInputs {

    private static final AtomicInteger tempFileUniquifierForVirtualInputWrites =
        new AtomicInteger();

    private final Map<PathFragment, RootedPath> files;
    private final Map<VirtualActionInput, byte[]> virtualInputs;
    private final Map<PathFragment, PathFragment> symlinks;
    private final Map<Root, Path> sourceRootBindMounts;

    private static final SandboxInputs EMPTY_INPUTS =
        new SandboxInputs(
            ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of(), ImmutableMap.of());

    public SandboxInputs(
        Map<PathFragment, RootedPath> files,
        Map<VirtualActionInput, byte[]> virtualInputs,
        Map<PathFragment, PathFragment> symlinks,
        Map<Root, Path> sourceRootBindMounts) {
      this.files = files;
      this.virtualInputs = virtualInputs;
      this.symlinks = symlinks;
      this.sourceRootBindMounts = sourceRootBindMounts;
    }

    public static SandboxInputs getEmptyInputs() {
      return EMPTY_INPUTS;
    }

    public Map<PathFragment, RootedPath> getFiles() {
      return files;
    }

    public Map<PathFragment, PathFragment> getSymlinks() {
      return symlinks;
    }

    public Map<Root, Path> getSourceRootBindMounts() {
      return sourceRootBindMounts;
    }

    /**
     * Materializes a single virtual input inside the given execroot.
     *
     * <p>When materializing inputs under a new sandbox exec root, we can expect the input to not
     * exist, but we cannot make the same assumption for the non-sandboxed exec root therefore, we
     * may need to delete existing files.
     *
     * @param input virtual input to materialize
     * @param execroot path to the execroot under which to materialize the virtual input
     * @param isExecRootSandboxed whether the execroot is sandboxed.
     * @return digest of written virtual input
     * @throws IOException if the virtual input cannot be materialized
     */
    private static byte[] materializeVirtualInput(
        VirtualActionInput input, Path execroot, boolean isExecRootSandboxed) throws IOException {
      if (input instanceof EmptyActionInput) {
        return new byte[0];
      }

      Path outputPath = execroot.getRelative(input.getExecPath());
      if (isExecRootSandboxed) {
        return atomicallyWriteVirtualInput(
            input,
            outputPath,
            // When 2 actions try to atomically create the same virtual input, they need to have a
            // different suffix for the temporary file in order to avoid racy write to the same one.
            ".sandbox" + tempFileUniquifierForVirtualInputWrites.incrementAndGet());
      }

      if (outputPath.exists()) {
        outputPath.delete();
      }
      outputPath.getParentDirectory().createDirectoryAndParents();
      return writeVirtualInputTo(input, outputPath);
    }

    public ImmutableMap<VirtualActionInput, byte[]> getVirtualInputDigests() {
      return ImmutableMap.copyOf(virtualInputs);
    }

    /**
     * Returns a new SandboxInputs instance with only the inputs/symlinks listed in {@code allowed}
     * included.
     */
    public SandboxInputs limitedCopy(Set<PathFragment> allowed) {
      Map<PathFragment, RootedPath> limitedFiles = Maps.filterKeys(files, allowed::contains);
      Map<PathFragment, PathFragment> limitedSymlinks =
          Maps.filterKeys(symlinks, allowed::contains);
      Set<Root> usedRoots =
          new HashSet<>(Maps.transformValues(limitedFiles, RootedPath::getRoot).values());
      Map<Root, Path> limitedSourceRoots =
          Maps.filterKeys(sourceRootBindMounts, usedRoots::contains);

      return new SandboxInputs(
          limitedFiles, ImmutableMap.of(), limitedSymlinks, limitedSourceRoots);
    }

    @Override
    public String toString() {
      return "Files: " + files + "\nVirtualInputs: " + virtualInputs + "\nSymlinks: " + symlinks;
    }
  }

  private static byte[] writeVirtualInputTo(VirtualActionInput input, Path target)
      throws IOException {
    byte[] digest;
    try (OutputStream out = target.getOutputStream();
        HashingOutputStream hashingOut =
            new HashingOutputStream(
                target.getFileSystem().getDigestFunction().getHashFunction(), out)) {
      input.writeTo(hashingOut);
      digest = hashingOut.hash().asBytes();
    }
    // Some of the virtual inputs can be executed, e.g. embedded tools. Setting executable flag for
    // other is fine since that is only more permissive. Please note that for action outputs (e.g.
    // file write, where the user can specify executable flag), we will have artifacts which do not
    // go through this code path.
    target.setExecutable(true);
    return digest;
  }

  /**
   * Returns the appropriate {@link RootedPath} for a Fileset symlink.
   *
   * <p>Filesets are weird because sometimes exec paths of the {@link ActionInput}s in them are not
   * relative, as exec paths should be, but absolute and point to under one of the package roots. In
   * order to handle this, if we find such an absolute exec path, we iterate over the package path
   * entries to turn it into a {@link RootedPath}.
   *
   * <p>The inputs to this function should be symlinks that are contained within Filesets; in
   * particular, this is different from "unresolved symlinks" in that Fileset contents are regular
   * files (but implemented by symlinks in the output tree) whose contents matter and unresolved
   * symlinks are symlinks for which the important content is the result of {@code readlink()}
   */
  private static RootedPath processFilesetSymlink(
      PathFragment symlink, ImmutableList<Root> packageRoots) {
    for (Root packageRoot : packageRoots) {
      if (packageRoot.contains(symlink)) {
        return RootedPath.toRootedPath(packageRoot, packageRoot.relativize(symlink));
      }
    }

    throw new IllegalStateException(
        String.format(
            "absolute action input path '%s' not found under package roots",
            symlink.getPathString()));
  }

  /**
   * Returns the inputs of a Spawn as a map of PathFragments relative to an execRoot to paths in the
   * host filesystem where the input files can be found.
   *
   * @param inputMap the map of action inputs and where they should be visible in the action
   * @param execRootPath the exec root from the point of view of the Bazel server
   * @param withinSandboxExecRootPath the exec root from within the sandbox (different from {@code
   *     execRootPath} because the sandbox does magic with fiile system namespaces)
   * @param packageRoots the package path entries during this build
   * @param sandboxSourceRoots the directory where source roots are mapped within the sandbox
   * @throws IOException if processing symlinks fails
   */
  public SandboxInputs processInputFiles(
      Map<PathFragment, ActionInput> inputMap,
      Path execRootPath,
      Path withinSandboxExecRootPath,
      ImmutableList<Root> packageRoots,
      Path sandboxSourceRoots)
      throws IOException {
    Root withinSandboxExecRoot = Root.fromPath(withinSandboxExecRootPath);
    Root execRoot =
        withinSandboxExecRootPath.equals(execRootPath)
            ? withinSandboxExecRoot
            : Root.fromPath(execRootPath);

    Map<PathFragment, RootedPath> inputFiles = new TreeMap<>();
    Map<PathFragment, PathFragment> inputSymlinks = new TreeMap<>();
    Map<VirtualActionInput, byte[]> virtualInputs = new HashMap<>();
    Map<Root, Root> sourceRootToSandboxSourceRoot = new TreeMap<>();

    for (Map.Entry<PathFragment, ActionInput> e : inputMap.entrySet()) {
      PathFragment pathFragment = e.getKey();
      ActionInput actionInput = e.getValue();
      if (actionInput instanceof VirtualActionInput) {
        byte[] digest =
            SandboxInputs.materializeVirtualInput(
                (VirtualActionInput) actionInput, execRootPath, /* isExecRootSandboxed= */ true);
        virtualInputs.put((VirtualActionInput) actionInput, digest);
      }

      if (actionInput.isSymlink()) {
        Path inputPath = execRoot.getRelative(actionInput.getExecPath());
        inputSymlinks.put(pathFragment, inputPath.readSymbolicLink());
      } else {
        RootedPath inputPath;

        if (actionInput instanceof EmptyActionInput) {
          inputPath = null;
        } else if (actionInput instanceof Artifact) {
          Artifact inputArtifact = (Artifact) actionInput;
          if (inputArtifact.isSourceArtifact() && sandboxSourceRoots != null) {
            Root sourceRoot = inputArtifact.getRoot().getRoot();
            if (!sourceRootToSandboxSourceRoot.containsKey(sourceRoot)) {
              int next = sourceRootToSandboxSourceRoot.size();
              sourceRootToSandboxSourceRoot.put(
                  sourceRoot,
                  Root.fromPath(sandboxSourceRoots.getRelative(Integer.toString(next))));
            }

            inputPath =
                RootedPath.toRootedPath(
                    sourceRootToSandboxSourceRoot.get(sourceRoot),
                    inputArtifact.getRootRelativePath());
          } else {
            inputPath = RootedPath.toRootedPath(withinSandboxExecRoot, inputArtifact.getExecPath());
          }
        } else {
          PathFragment execPath = actionInput.getExecPath();
          if (execPath.isAbsolute()) {
            // This happens for ActionInputs that are part of Filesets (see the Javadoc on
            // processFilesetSymlink())
            inputPath = processFilesetSymlink(actionInput.getExecPath(), packageRoots);
          } else {
            inputPath = RootedPath.toRootedPath(execRoot, actionInput.getExecPath());
          }
        }

        inputFiles.put(pathFragment, inputPath);
      }
    }

    Map<Root, Path> sandboxRootToSourceRoot = new TreeMap<>();
    for (Map.Entry<Root, Root> entry : sourceRootToSandboxSourceRoot.entrySet()) {
      sandboxRootToSourceRoot.put(entry.getValue(), entry.getKey().asPath());
    }

    return new SandboxInputs(inputFiles, virtualInputs, inputSymlinks, sandboxRootToSourceRoot);
  }

  /** The file and directory outputs of a sandboxed spawn. */
  @AutoValue
  public abstract static class SandboxOutputs {
    public abstract ImmutableSet<PathFragment> files();

    public abstract ImmutableSet<PathFragment> dirs();

    private static final SandboxOutputs EMPTY_OUTPUTS =
        SandboxOutputs.create(ImmutableSet.of(), ImmutableSet.of());

    public static SandboxOutputs create(
        ImmutableSet<PathFragment> files, ImmutableSet<PathFragment> dirs) {
      return new AutoValue_SandboxHelpers_SandboxOutputs(files, dirs);
    }

    public static SandboxOutputs getEmptyInstance() {
      return EMPTY_OUTPUTS;
    }
  }

  public SandboxOutputs getOutputs(Spawn spawn) {
    ImmutableSet.Builder<PathFragment> files = ImmutableSet.builder();
    ImmutableSet.Builder<PathFragment> dirs = ImmutableSet.builder();
    for (ActionInput output : spawn.getOutputFiles()) {
      PathFragment path = PathFragment.create(output.getExecPathString());
      if (output instanceof Artifact && ((Artifact) output).isTreeArtifact()) {
        dirs.add(path);
      } else {
        files.add(path);
      }
    }
    return SandboxOutputs.create(files.build(), dirs.build());
  }

  /**
   * Returns true if the build options are set in a way that requires network access for all
   * actions. This is separate from {@link
   * com.google.devtools.build.lib.actions.Spawns#requiresNetwork} to avoid having to keep a
   * reference to the full set of build options (and also for performance, since this only needs to
   * be checked once-per-build).
   */
  boolean shouldAllowNetwork(OptionsParsingResult buildOptions) {
    // Allow network access, when --java_debug is specified, otherwise we can't connect to the
    // remote debug server of the test. This intentionally overrides the "block-network" execution
    // tag.
    return buildOptions
        .getOptions(TestConfiguration.TestOptions.class)
        .testArguments
        .contains("--wrapper_script_flag=--debug");
  }
}
