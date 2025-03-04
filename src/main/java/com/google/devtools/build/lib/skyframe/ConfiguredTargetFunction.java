// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.flogger.GoogleLogger;
import com.google.devtools.build.lib.actions.MutableActionGraph.ActionConflictException;
import com.google.devtools.build.lib.analysis.AnalysisRootCauseEvent;
import com.google.devtools.build.lib.analysis.AspectResolver;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment;
import com.google.devtools.build.lib.analysis.CachingAnalysisEnvironment.MissingDepException;
import com.google.devtools.build.lib.analysis.ConfiguredAspect;
import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.ConfiguredTargetValue;
import com.google.devtools.build.lib.analysis.Dependency;
import com.google.devtools.build.lib.analysis.DependencyKey;
import com.google.devtools.build.lib.analysis.DependencyKind;
import com.google.devtools.build.lib.analysis.DependencyResolver;
import com.google.devtools.build.lib.analysis.DuplicateException;
import com.google.devtools.build.lib.analysis.EmptyConfiguredTarget;
import com.google.devtools.build.lib.analysis.ExecGroupCollection;
import com.google.devtools.build.lib.analysis.ExecGroupCollection.InvalidExecGroupException;
import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException;
import com.google.devtools.build.lib.analysis.PlatformConfiguration;
import com.google.devtools.build.lib.analysis.ResolvedToolchainContext;
import com.google.devtools.build.lib.analysis.TargetAndConfiguration;
import com.google.devtools.build.lib.analysis.ToolchainCollection;
import com.google.devtools.build.lib.analysis.ToolchainContext;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.BuildOptionsView;
import com.google.devtools.build.lib.analysis.config.ConfigConditions;
import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider;
import com.google.devtools.build.lib.analysis.config.ConfigurationResolver;
import com.google.devtools.build.lib.analysis.config.DependencyEvaluationException;
import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement;
import com.google.devtools.build.lib.analysis.config.transitions.PatchTransition;
import com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget;
import com.google.devtools.build.lib.analysis.constraints.IncompatibleTargetChecker;
import com.google.devtools.build.lib.analysis.platform.PlatformInfo;
import com.google.devtools.build.lib.analysis.test.AnalysisFailurePropagationException;
import com.google.devtools.build.lib.bugreport.BugReport;
import com.google.devtools.build.lib.causes.AnalysisFailedCause;
import com.google.devtools.build.lib.causes.Cause;
import com.google.devtools.build.lib.causes.LoadingFailedCause;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.events.Event;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.events.StoredEventHandler;
import com.google.devtools.build.lib.packages.Aspect;
import com.google.devtools.build.lib.packages.BuildType;
import com.google.devtools.build.lib.packages.ExecGroup;
import com.google.devtools.build.lib.packages.NoSuchTargetException;
import com.google.devtools.build.lib.packages.NonconfigurableAttributeMapper;
import com.google.devtools.build.lib.packages.Package;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClassProvider;
import com.google.devtools.build.lib.packages.Target;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.Analysis;
import com.google.devtools.build.lib.server.FailureDetails.Analysis.Code;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.SkyframeExecutor.BuildViewProvider;
import com.google.devtools.build.lib.util.DetailedExitCode;
import com.google.devtools.build.lib.util.DetailedExitCode.DetailedExitCodeComparator;
import com.google.devtools.build.lib.util.OrderedSetMultimap;
import com.google.devtools.build.skyframe.SkyFunction;
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState;
import com.google.devtools.build.skyframe.SkyFunctionException;
import com.google.devtools.build.skyframe.SkyKey;
import com.google.devtools.build.skyframe.SkyValue;
import com.google.devtools.build.skyframe.SkyframeLookupResult;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * SkyFunction for {@link ConfiguredTargetValue}s.
 *
 * <p>This class, together with {@link AspectFunction} drives the analysis phase. For more
 * information, see {@link com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory}.
 *
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 */
public final class ConfiguredTargetFunction implements SkyFunction {
  private static final GoogleLogger logger = GoogleLogger.forEnclosingClass();

  /**
   * Attempt to find a {@link ConfiguredValueCreationException} in a {@link ToolchainException}, or
   * its causes.
   *
   * <p>If one cannot be found, null is returned.
   */
  @Nullable
  public static ConfiguredValueCreationException asConfiguredValueCreationException(
      ToolchainException e) {
    for (Throwable cause = e.getCause();
        cause != null && cause != cause.getCause();
        cause = cause.getCause()) {
      if (cause instanceof ConfiguredValueCreationException) {
        return (ConfiguredValueCreationException) cause;
      }
    }
    return null;
  }

  private final BuildViewProvider buildViewProvider;
  private final RuleClassProvider ruleClassProvider;
  // TODO(b/185987566): Remove this semaphore.
  private final AtomicReference<Semaphore> cpuBoundSemaphore;
  @Nullable private final ConfiguredTargetProgressReceiver configuredTargetProgress;

  /**
   * Indicates whether the set of packages transitively loaded for a given {@link
   * ConfiguredTargetValue} will be needed later (see {@link
   * com.google.devtools.build.lib.analysis.ConfiguredObjectValue#getTransitivePackages}). If not,
   * they are not collected and stored.
   */
  private final boolean storeTransitivePackages;

  private final boolean shouldUnblockCpuWorkWhenFetchingDeps;

  ConfiguredTargetFunction(
      BuildViewProvider buildViewProvider,
      RuleClassProvider ruleClassProvider,
      AtomicReference<Semaphore> cpuBoundSemaphore,
      boolean storeTransitivePackages,
      boolean shouldUnblockCpuWorkWhenFetchingDeps,
      @Nullable ConfiguredTargetProgressReceiver configuredTargetProgress) {
    this.buildViewProvider = buildViewProvider;
    this.ruleClassProvider = ruleClassProvider;
    this.cpuBoundSemaphore = cpuBoundSemaphore;
    this.storeTransitivePackages = storeTransitivePackages;
    this.shouldUnblockCpuWorkWhenFetchingDeps = shouldUnblockCpuWorkWhenFetchingDeps;
    this.configuredTargetProgress = configuredTargetProgress;
  }

  private void maybeAcquireSemaphoreWithLogging(SkyKey key) throws InterruptedException {
    if (cpuBoundSemaphore.get() == null) {
      return;
    }
    Stopwatch stopwatch = Stopwatch.createStarted();
    cpuBoundSemaphore.get().acquire();
    long elapsedTime = stopwatch.elapsed().toMillis();
    if (elapsedTime > 5) {
      logger.atInfo().atMostEvery(10, TimeUnit.SECONDS).log(
          "Spent %s milliseconds waiting for lock acquisition for %s", elapsedTime, key);
    }
  }

  private void maybeReleaseSemaphore() {
    if (cpuBoundSemaphore.get() != null) {
      cpuBoundSemaphore.get().release();
    }
  }

  static class State implements SkyKeyComputeState {
    /** Null if ConfiguredTargetFuncton is not storing this information. */
    @Nullable NestedSetBuilder<Package> transitivePackages;

    NestedSetBuilder<Cause> transitiveRootCauses = NestedSetBuilder.stableOrder();

    @Nullable TargetAndConfiguration targetAndConfiguration;

    ComputeDependenciesState computeDependenciesState = new ComputeDependenciesState();

    State(boolean storeTransitivePackages) {
      this.transitivePackages = storeTransitivePackages ? NestedSetBuilder.stableOrder() : null;
    }
  }

  static class ComputeDependenciesState implements SkyKeyComputeState {
    /** Null if not yet computed or if {@link #resolveConfigurationsResult} is non-null. */
    @Nullable private OrderedSetMultimap<DependencyKind, DependencyKey> dependentNodeMapResult;

    /** Null if not yet computed or if {@link #computeDependenciesResult} is non-null. */
    @Nullable private OrderedSetMultimap<DependencyKind, Dependency> resolveConfigurationsResult;

    /** Null if not yet computed or if {@link #computeDependenciesResult} is non-null. */
    @Nullable
    private Map<SkyKey, ConfiguredTargetAndData> resolveConfiguredTargetDependenciesResult;

    /** Null if not yet computed or if {@link #computeDependenciesResult} is non-null. */
    @Nullable
    private OrderedSetMultimap<Dependency, ConfiguredAspect> resolveAspectDependenciesResult;

    /**
     * Non-null if all the work in {@link #computeDependencies} is already done. This field contains
     * the result.
     */
    @Nullable
    private OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> computeDependenciesResult;

    /**
     * Non-null if either {@link #resolveConfigurationsResult} or {@link #computeDependenciesResult}
     * are non-null. This field contains events (from {@link
     * ConfigurationResolver#resolveConfigurations}) that should be replayed.
     *
     * <p>When {@link #resolveConfigurationsResult} or {@link #computeDependenciesResult} are
     * non-null (e.g. populated on a previous call to {@link #computeDependencies} on a previous
     * call to {@link #compute}), we don't freshly do the work that would cause these events to be
     * freshly emitted. So instead we replay these events from the actual call to {@link
     * ConfigurationResolver#resolveConfigurations} we did in the past. This is important because
     * Skyframe retains and uses only the events emitted to {@code env.getListener()} on a call to
     * {@link #compute} that had no missing deps. That is, if our earlier {@link #compute}'s call to
     * {@link ConfigurationResolver#resolveConfigurations} emitted events to {@code
     * env.getListener()}, and that {@link #compute} call returned null, then those events would be
     * thrown away.
     */
    @Nullable private StoredEventHandler storedEventHandlerFromResolveConfigurations;
  }

  @Nullable
  @Override
  public SkyValue compute(SkyKey key, Environment env)
      throws ReportedException, UnreportedException, InterruptedException {
    State state = env.getState(() -> new State(storeTransitivePackages));

    if (shouldUnblockCpuWorkWhenFetchingDeps) {
      // Fetching blocks on other resources, so we don't want to hold on to the semaphore meanwhile.
      env =
          new StateInformingSkyFunctionEnvironment(
              env,
              /*preFetch=*/ this::maybeReleaseSemaphore,
              /*postFetch=*/ () -> maybeAcquireSemaphoreWithLogging(key));
    }

    ConfiguredTargetKey configuredTargetKey = (ConfiguredTargetKey) key.argument();
    TargetAndConfiguration targetAndConfiguration =
        getTargetAndConfiguration(configuredTargetKey, state, env);
    if (targetAndConfiguration == null) {
      return null;
    }
    Target target = targetAndConfiguration.getTarget();
    if (target.isConfigurable() == (configuredTargetKey.getConfigurationKey() == null)) {
      // We somehow ended up in a target that requires a non-null configuration as a dependency of
      // one that requires a null configuration or the other way round. This is always an error, but
      // we need to analyze the dependencies of the latter target to realize that. Short-circuit the
      // evaluation to avoid doing useless work and running code with a null configuration that's
      // not prepared for it.
      return new NonRuleConfiguredTargetValue(
          new EmptyConfiguredTarget(target.getLabel(), configuredTargetKey.getConfigurationKey()),
          state.transitivePackages == null ? null : state.transitivePackages.build());
    }

    SkyframeBuildView view = buildViewProvider.getSkyframeBuildView();
    SkyframeDependencyResolver resolver = new SkyframeDependencyResolver(env);
    ToolchainCollection<UnloadedToolchainContext> unloadedToolchainContexts = null;
    ExecGroupCollection.Builder execGroupCollectionBuilder;

    // TODO(janakr): this call may tie up this thread indefinitely, reducing the parallelism of
    //  Skyframe. This is a strict improvement over the prior state of the code, in which we ran
    //  with #processors threads, but ideally we would call #tryAcquire here, and if we failed,
    //  would exit this SkyFunction and restart it when permits were available.
    maybeAcquireSemaphoreWithLogging(key);
    try {
      // Determine what toolchains are needed by this target.
      ComputedToolchainContexts result =
          computeUnloadedToolchainContexts(
              env,
              ruleClassProvider,
              targetAndConfiguration,
              configuredTargetKey.getExecutionPlatformLabel());
      if (env.valuesMissing()) {
        return null;
      }
      unloadedToolchainContexts = result.toolchainCollection;
      execGroupCollectionBuilder = result.execGroupCollectionBuilder;
      PlatformInfo platformInfo =
          unloadedToolchainContexts != null ? unloadedToolchainContexts.getTargetPlatform() : null;

      // Get the configuration targets that trigger this rule's configurable attributes.
      ConfigConditions configConditions =
          getConfigConditions(
              env,
              targetAndConfiguration,
              state.transitivePackages,
              platformInfo,
              state.transitiveRootCauses);
      if (env.valuesMissing()) {
        return null;
      }
      // TODO(ulfjack): ConfiguredAttributeMapper (indirectly used from computeDependencies) isn't
      // safe to use if there are missing config conditions, so we stop here, but only if there are
      // config conditions - though note that we can't check if configConditions is non-empty - it
      // may be empty for other reasons. It would be better to continue here so that we can collect
      // more root causes during computeDependencies.
      // Note that this doesn't apply to AspectFunction, because aspects can't have configurable
      // attributes.
      if (!state.transitiveRootCauses.isEmpty()
          && !Objects.equals(configConditions, ConfigConditions.EMPTY)) {
        NestedSet<Cause> causes = state.transitiveRootCauses.build();
        env.getListener()
            .handle(Event.error(target.getLocation(), "Cannot compute config conditions"));
        throw new ReportedException(
            new ConfiguredValueCreationException(
                targetAndConfiguration,
                "Cannot compute config conditions",
                causes,
                getPrioritizedDetailedExitCode(causes)));
      }

      Optional<RuleConfiguredTargetValue> incompatibleTarget =
          IncompatibleTargetChecker.createDirectlyIncompatibleTarget(
              targetAndConfiguration,
              configConditions,
              env,
              platformInfo,
              state.transitivePackages);
      if (incompatibleTarget == null) {
        return null;
      }
      if (incompatibleTarget.isPresent()) {
        return incompatibleTarget.get();
      }

      // Calculate the dependencies of this target.
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap =
          computeDependencies(
              state.computeDependenciesState,
              state.transitivePackages,
              state.transitiveRootCauses,
              env,
              resolver,
              targetAndConfiguration,
              ImmutableList.of(),
              configConditions.asProviders(),
              unloadedToolchainContexts == null
                  ? null
                  : unloadedToolchainContexts.asToolchainContexts(),
              ruleClassProvider,
              view);
      if (!state.transitiveRootCauses.isEmpty()) {
        NestedSet<Cause> causes = state.transitiveRootCauses.build();
        // TODO(bazel-team): consider reporting the error in this class vs. exporting it for
        // BuildTool to handle. Calling code needs to be untangled for that to work and pass tests.
        throw new UnreportedException(
            new ConfiguredValueCreationException(
                targetAndConfiguration,
                "Analysis failed",
                causes,
                getPrioritizedDetailedExitCode(causes)));
      }
      if (env.valuesMissing()) {
        return null;
      }
      Preconditions.checkNotNull(depValueMap);

      incompatibleTarget =
          IncompatibleTargetChecker.createIndirectlyIncompatibleTarget(
              targetAndConfiguration,
              depValueMap,
              configConditions,
              platformInfo,
              state.transitivePackages);
      if (incompatibleTarget.isPresent()) {
        return incompatibleTarget.get();
      }

      // Load the requested toolchains into the ToolchainContext, now that we have dependencies.
      ToolchainCollection<ResolvedToolchainContext> toolchainContexts = null;
      if (unloadedToolchainContexts != null) {
        String targetDescription = target.toString();
        ToolchainCollection.Builder<ResolvedToolchainContext> contextsBuilder =
            ToolchainCollection.builder();
        for (Map.Entry<String, UnloadedToolchainContext> unloadedContext :
            unloadedToolchainContexts.getContextMap().entrySet()) {
          Set<ConfiguredTargetAndData> toolchainDependencies =
              depValueMap.get(DependencyKind.forExecGroup(unloadedContext.getKey()));
          contextsBuilder.addContext(
              unloadedContext.getKey(),
              ResolvedToolchainContext.load(
                  unloadedContext.getValue(), targetDescription, toolchainDependencies));
        }
        toolchainContexts = contextsBuilder.build();
      }

      ConfiguredTargetValue ans =
          createConfiguredTarget(
              view,
              env,
              targetAndConfiguration,
              configuredTargetKey,
              depValueMap,
              configConditions,
              toolchainContexts,
              execGroupCollectionBuilder,
              state.transitivePackages);
      if (ans != null && configuredTargetProgress != null) {
        configuredTargetProgress.doneConfigureTarget();
      }
      return ans;
    } catch (DependencyEvaluationException e) {
      String errorMessage = e.getMessage();
      if (!e.depReportedOwnError()) {
        env.getListener().handle(Event.error(e.getLocation(), e.getMessage()));
      }

      ConfiguredValueCreationException cvce = null;
      if (e.getCause() instanceof ConfiguredValueCreationException) {
        cvce = (ConfiguredValueCreationException) e.getCause();

        // Check if this is caused by an unresolved toolchain, and report it as such.
        if (unloadedToolchainContexts != null) {
          ImmutableSet<Label> requiredToolchains =
              unloadedToolchainContexts.getResolvedToolchains();
          Set<Label> toolchainDependencyErrors =
              cvce.getRootCauses().toList().stream()
                  .map(Cause::getLabel)
                  .filter(requiredToolchains::contains)
                  .collect(ImmutableSet.toImmutableSet());

          if (!toolchainDependencyErrors.isEmpty()) {
            errorMessage = "errors encountered resolving toolchains for " + target.getLabel();
            env.getListener().handle(Event.error(target.getLocation(), errorMessage));
          }
        }
      }

      throw new ReportedException(
          cvce != null
              ? cvce
              : new ConfiguredValueCreationException(
                  targetAndConfiguration, errorMessage, null, e.getDetailedExitCode()));
    } catch (ConfiguredValueCreationException e) {
      if (!e.getMessage().isEmpty()) {
        // Report the error to the user.
        env.getListener().handle(Event.error(e.getLocation(), e.getMessage()));
      }
      throw new ReportedException(e);
    } catch (AspectCreationException e) {
      throw new ReportedException(
          new ConfiguredValueCreationException(
              targetAndConfiguration, e.getMessage(), e.getCauses(), e.getDetailedExitCode()));
    } catch (ToolchainException e) {
      String message =
          String.format(
              "While resolving toolchains for target %s: %s", target.getLabel(), e.getMessage());
      // We need to throw a ConfiguredValueCreationException, so either find one or make one.
      ConfiguredValueCreationException cvce = asConfiguredValueCreationException(e);
      if (cvce == null) {
        cvce =
            new ConfiguredValueCreationException(
                targetAndConfiguration, message, null, e.getDetailedExitCode());
      }
      if (!message.isEmpty()) {
        // Report the error to the user.
        env.getListener().handle(Event.error(target.getLocation(), message));
      }
      throw new ReportedException(cvce);
    } finally {
      maybeReleaseSemaphore();
    }
  }

  @Nullable
  private static TargetAndConfiguration getTargetAndConfiguration(
      ConfiguredTargetKey configuredTargetKey, State state, Environment env)
      throws InterruptedException, ReportedException {
    if (state.targetAndConfiguration != null) {
      return state.targetAndConfiguration;
    }
    Label label = configuredTargetKey.getLabel();
    BuildConfigurationValue configuration = null;
    ImmutableSet<SkyKey> packageAndMaybeConfiguration;
    SkyKey packageKey = PackageValue.key(label.getPackageIdentifier());
    SkyKey configurationKeyMaybe = configuredTargetKey.getConfigurationKey();
    if (configurationKeyMaybe == null) {
      packageAndMaybeConfiguration = ImmutableSet.of(packageKey);
    } else {
      packageAndMaybeConfiguration = ImmutableSet.of(packageKey, configurationKeyMaybe);
    }
    SkyframeLookupResult packageAndMaybeConfigurationValues =
        env.getValuesAndExceptions(packageAndMaybeConfiguration);
    if (env.valuesMissing()) {
      return null;
    }
    PackageValue packageValue = (PackageValue) packageAndMaybeConfigurationValues.get(packageKey);
    if (packageValue == null) {
      return null;
    }
    Package pkg = packageValue.getPackage();
    if (configurationKeyMaybe != null) {
      configuration =
          (BuildConfigurationValue) packageAndMaybeConfigurationValues.get(configurationKeyMaybe);
    }
    // TODO(ulfjack): This tries to match the logic in TransitiveTargetFunction /
    // TargetMarkerFunction. Maybe we can merge the two?
    Target target;
    try {
      target = pkg.getTarget(label.getName());
    } catch (NoSuchTargetException e) {
      if (!e.getMessage().isEmpty()) {
        env.getListener().handle(Event.error(pkg.getBuildFile().getLocation(), e.getMessage()));
      }
      throw new ReportedException(
          new ConfiguredValueCreationException(
              pkg.getBuildFile().getLocation(),
              e.getMessage(),
              label,
              configuration.getEventId(),
              null,
              e.getDetailedExitCode()));
    }
    if (pkg.containsErrors()) {
      FailureDetail failureDetail = pkg.contextualizeFailureDetailForTarget(target);
      state.transitiveRootCauses.add(
          new LoadingFailedCause(label, DetailedExitCode.of(failureDetail)));
    }
    if (state.transitivePackages != null) {
      state.transitivePackages.add(pkg);
    }
    state.targetAndConfiguration = new TargetAndConfiguration(target, configuration);
    return state.targetAndConfiguration;
  }

  /**
   * Simple wrapper to allow returning two variables from {@link #computeUnloadedToolchainContexts}.
   */
  @VisibleForTesting
  public static class ComputedToolchainContexts {
    @Nullable public ToolchainCollection<UnloadedToolchainContext> toolchainCollection = null;
    public ExecGroupCollection.Builder execGroupCollectionBuilder =
        ExecGroupCollection.emptyBuilder();
  }

  @VisibleForTesting
  @Nullable
  public static ComputedToolchainContexts computeUnloadedToolchainContexts(
      Environment env,
      RuleClassProvider ruleClassProvider,
      TargetAndConfiguration targetAndConfig,
      @Nullable Label parentExecutionPlatformLabel)
      throws InterruptedException, ToolchainException {

    // We can only perform toolchain resolution on Targets and Aspects.
    if (!(targetAndConfig.getTarget() instanceof Rule)) {
      return new ComputedToolchainContexts();
    }

    Label label = targetAndConfig.getLabel();
    Rule rule = ((Rule) targetAndConfig.getTarget());
    BuildConfigurationValue configuration = targetAndConfig.getConfiguration();

    ImmutableSet<ToolchainTypeRequirement> toolchainTypes =
        rule.getRuleClassObject().getToolchainTypes();
    // Collect local (target, rule) constraints for filtering out execution platforms.
    ImmutableSet<Label> defaultExecConstraintLabels =
        getExecutionPlatformConstraints(
            rule, configuration.getFragment(PlatformConfiguration.class));
    ImmutableMap<String, ExecGroup> execGroups = rule.getRuleClassObject().getExecGroups();

    // The toolchain context's options are the parent rule's options with manual trimming
    // auto-applied. This means toolchains don't inherit feature flags. This helps build
    // performance: if the toolchain context had the exact same configuration of its parent and that
    // included feature flags, all the toolchain's dependencies would apply this transition
    // individually. That creates a lot more potentially expensive applications of that transition
    // (especially since manual trimming applies to every configured target in the build).
    //
    // In other words: without this modification:
    // parent rule -> toolchain context -> toolchain
    //     -> toolchain dep 1 # applies manual trimming to remove feature flags
    //     -> toolchain dep 2 # applies manual trimming to remove feature flags
    //     ...
    //
    // With this modification:
    // parent rule -> toolchain context # applies manual trimming to remove feature flags
    //     -> toolchain
    //         -> toolchain dep 1
    //         -> toolchain dep 2
    //         ...
    //
    // None of this has any effect on rules that don't utilize manual trimming.
    PatchTransition toolchainTaggedTrimmingTransition =
        ((ConfiguredRuleClassProvider) ruleClassProvider).getToolchainTaggedTrimmingTransition();
    BuildOptions toolchainOptions =
        toolchainTaggedTrimmingTransition.patch(
            new BuildOptionsView(
                configuration.getOptions(),
                toolchainTaggedTrimmingTransition.requiresOptionFragments()),
            env.getListener());

    BuildConfigurationKey toolchainConfig =
        BuildConfigurationKey.withoutPlatformMapping(toolchainOptions);

    PlatformConfiguration platformConfig = configuration.getFragment(PlatformConfiguration.class);
    return computeUnloadedToolchainContexts(
        env,
        label,
        platformConfig != null && rule.useToolchainResolution(),
        l -> platformConfig != null && platformConfig.debugToolchainResolution(l),
        toolchainConfig,
        toolchainTypes,
        defaultExecConstraintLabels,
        execGroups,
        parentExecutionPlatformLabel);
  }

  /**
   * Returns the toolchain context and exec group collection for this target. The toolchain context
   * may be {@code null} if the target doesn't use toolchains.
   *
   * <p>This involves Skyframe evaluation: callers should check {@link Environment#valuesMissing()
   * to check the result is valid.
   */
  @Nullable
  static ComputedToolchainContexts computeUnloadedToolchainContexts(
      Environment env,
      Label label,
      boolean useToolchainResolution,
      Predicate<Label> debugResolution,
      BuildConfigurationKey configurationKey,
      ImmutableSet<ToolchainTypeRequirement> toolchainTypes,
      ImmutableSet<Label> defaultExecConstraintLabels,
      ImmutableMap<String, ExecGroup> execGroups,
      @Nullable Label parentExecutionPlatformLabel)
      throws InterruptedException, ToolchainException {

    // Create a merged version of the exec groups that handles exec group inheritance properly.
    ExecGroup defaultExecGroup =
        ExecGroup.builder()
            .toolchainTypes(toolchainTypes)
            .execCompatibleWith(defaultExecConstraintLabels)
            .copyFrom(null)
            .build();
    ExecGroupCollection.Builder execGroupCollectionBuilder =
        ExecGroupCollection.builder(defaultExecGroup, execGroups);

    // Short circuit and end now if this target doesn't require toolchain resolution.
    if (!useToolchainResolution) {
      ComputedToolchainContexts result = new ComputedToolchainContexts();
      result.execGroupCollectionBuilder = execGroupCollectionBuilder;
      return result;
    }

    Map<String, ToolchainContextKey> toolchainContextKeys = new HashMap<>();
    String targetUnloadedToolchainContext = "target-unloaded-toolchain-context";

    // Check if this specific target should be debugged for toolchain resolution.
    boolean debugTarget = debugResolution.test(label);

    ToolchainContextKey.Builder toolchainContextKeyBuilder =
        ToolchainContextKey.key()
            .configurationKey(configurationKey)
            .toolchainTypes(toolchainTypes)
            .execConstraintLabels(defaultExecConstraintLabels)
            .debugTarget(debugTarget);

    if (parentExecutionPlatformLabel != null) {
      // Find out what execution platform the parent used, and force that.
      // This should only be set for direct toolchain dependencies.
      toolchainContextKeyBuilder.forceExecutionPlatform(parentExecutionPlatformLabel);
    }

    ToolchainContextKey toolchainContextKey = toolchainContextKeyBuilder.build();
    toolchainContextKeys.put(targetUnloadedToolchainContext, toolchainContextKey);
    for (String name : execGroupCollectionBuilder.getExecGroupNames()) {
      ExecGroup execGroup = execGroupCollectionBuilder.getExecGroup(name);
      toolchainContextKeys.put(
          name,
          ToolchainContextKey.key()
              .configurationKey(configurationKey)
              .toolchainTypes(execGroup.toolchainTypes())
              .execConstraintLabels(execGroup.execCompatibleWith())
              .debugTarget(debugTarget)
              .build());
    }

    SkyframeLookupResult values = env.getValuesAndExceptions(toolchainContextKeys.values());

    boolean valuesMissing = env.valuesMissing();

    ToolchainCollection.Builder<UnloadedToolchainContext> toolchainContexts =
        valuesMissing ? null : ToolchainCollection.builder();
    for (Map.Entry<String, ToolchainContextKey> unloadedToolchainContextKey :
        toolchainContextKeys.entrySet()) {
      UnloadedToolchainContext unloadedToolchainContext =
          (UnloadedToolchainContext)
              values.getOrThrow(unloadedToolchainContextKey.getValue(), ToolchainException.class);
      if (valuesMissing != env.valuesMissing()) {
        BugReport.logUnexpected(
            "Value for: '%s' was missing, this should never happen",
            unloadedToolchainContextKey.getValue());
        break;
      }
      if (unloadedToolchainContext != null && unloadedToolchainContext.errorData() != null) {
        throw new NoMatchingPlatformException(unloadedToolchainContext.errorData());
      }
      if (!valuesMissing) {
        String execGroup = unloadedToolchainContextKey.getKey();
        if (execGroup.equals(targetUnloadedToolchainContext)) {
          toolchainContexts.addDefaultContext(unloadedToolchainContext);
        } else {
          toolchainContexts.addContext(execGroup, unloadedToolchainContext);
        }
      }
    }

    ComputedToolchainContexts result = new ComputedToolchainContexts();
    result.toolchainCollection = env.valuesMissing() ? null : toolchainContexts.build();
    result.execGroupCollectionBuilder = execGroupCollectionBuilder;
    return result;
  }

  /**
   * Returns the target-specific execution platform constraints, based on the rule definition and
   * any constraints added by the target, including those added for the target on the command line.
   */
  public static ImmutableSet<Label> getExecutionPlatformConstraints(
      Rule rule, PlatformConfiguration platformConfiguration) {
    if (platformConfiguration == null) {
      return ImmutableSet.of(); // See NoConfigTransition.
    }
    NonconfigurableAttributeMapper mapper = NonconfigurableAttributeMapper.of(rule);
    ImmutableSet.Builder<Label> execConstraintLabels = new ImmutableSet.Builder<>();

    execConstraintLabels.addAll(rule.getRuleClassObject().getExecutionPlatformConstraints());
    if (rule.getRuleClassObject()
        .hasAttr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)) {
      execConstraintLabels.addAll(
          mapper.get(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST));
    }

    execConstraintLabels.addAll(
        platformConfiguration.getAdditionalExecutionConstraintsFor(rule.getLabel()));

    return execConstraintLabels.build();
  }

  /**
   * Computes the direct dependencies of a node in the configured target graph (a configured target
   * or an aspects).
   *
   * <p>Returns null if Skyframe hasn't evaluated the required dependencies yet. In this case, the
   * caller should also return null to Skyframe.
   *
   * @param state the compute state
   * @param env the Skyframe environment
   * @param resolver the dependency resolver
   * @param ctgValue the label and the configuration of the node
   * @param configConditions the configuration conditions for evaluating the attributes of the node
   * @param toolchainContexts the toolchain context for this target
   * @param ruleClassProvider rule class provider for determining the right configuration fragments
   *     to apply to deps
   * @param buildView the build's {@link SkyframeBuildView}
   */
  // TODO(b/213351014): Make the control flow of this helper function more readable. This will
  //   involve making a corresponding change to State to match the control flow.
  @Nullable
  static OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> computeDependencies(
      ComputeDependenciesState state,
      @Nullable NestedSetBuilder<Package> transitivePackages,
      NestedSetBuilder<Cause> transitiveRootCauses,
      Environment env,
      SkyframeDependencyResolver resolver,
      TargetAndConfiguration ctgValue,
      Iterable<Aspect> aspects,
      ImmutableMap<Label, ConfigMatchingProvider> configConditions,
      @Nullable ToolchainCollection<ToolchainContext> toolchainContexts,
      RuleClassProvider ruleClassProvider,
      SkyframeBuildView buildView)
      throws DependencyEvaluationException, ConfiguredValueCreationException,
          AspectCreationException, InterruptedException {
    try {
      if (state.computeDependenciesResult != null) {
        state.storedEventHandlerFromResolveConfigurations.replayOn(env.getListener());
        return state.computeDependenciesResult;
      }

      OrderedSetMultimap<DependencyKind, Dependency> depValueNames;
      if (state.resolveConfigurationsResult != null) {
        depValueNames = state.resolveConfigurationsResult;
      } else {
        // Create the map from attributes to set of (target, transition) pairs.
        OrderedSetMultimap<DependencyKind, DependencyKey> initialDependencies;
        if (state.dependentNodeMapResult != null) {
          initialDependencies = state.dependentNodeMapResult;
        } else {
          BuildConfigurationValue configuration = ctgValue.getConfiguration();
          Label label = ctgValue.getLabel();
          try {
            initialDependencies =
                resolver.dependentNodeMap(
                    ctgValue,
                    aspects,
                    configConditions,
                    toolchainContexts,
                    transitiveRootCauses,
                    ((ConfiguredRuleClassProvider) ruleClassProvider)
                        .getTrimmingTransitionFactory());
          } catch (DependencyResolver.Failure e) {
            env.getListener()
                .post(new AnalysisRootCauseEvent(configuration, label, e.getMessage()));
            throw new DependencyEvaluationException(
                new ConfiguredValueCreationException(
                    e.getLocation(), e.getMessage(), label, configuration.getEventId(), null, null),
                // These errors occur within DependencyResolver, which is attached to the current
                // target. i.e. no dependent ConfiguredTargetFunction call happens to report its own
                // error.
                /*depReportedOwnError=*/ false);
          } catch (InconsistentAspectOrderException e) {
            throw new DependencyEvaluationException(e);
          }
          if (!env.valuesMissing()) {
            state.dependentNodeMapResult = initialDependencies;
          }
        }
        // Trim each dep's configuration so it only includes the fragments needed by its transitive
        // closure.
        ConfigurationResolver configResolver =
            new ConfigurationResolver(
                env,
                ctgValue,
                configConditions,
                buildView.getStarlarkTransitionCache());
        StoredEventHandler storedEventHandler = new StoredEventHandler();
        try {
          depValueNames =
              configResolver.resolveConfigurations(initialDependencies, storedEventHandler);
        } catch (ConfiguredValueCreationException e) {
          storedEventHandler.replayOn(env.getListener());
          throw e;
        }
        if (!env.valuesMissing()) {
          state.resolveConfigurationsResult = depValueNames;
          state.storedEventHandlerFromResolveConfigurations = storedEventHandler;

          // We won't need this anymore.
          state.dependentNodeMapResult = null;
        }
      }

      // Return early in case packages were not loaded yet. In theory, we could start configuring
      // dependent targets in loaded packages. However, that creates an artificial sync boundary
      // between loading all dependent packages (fast) and configuring some dependent targets (can
      // have a long tail).
      if (env.valuesMissing()) {
        return null;
      }

      // Resolve configured target dependencies and handle errors.
      Map<SkyKey, ConfiguredTargetAndData> depValues;
      if (state.resolveConfiguredTargetDependenciesResult != null) {
        depValues = state.resolveConfiguredTargetDependenciesResult;
      } else {
        depValues =
            resolveConfiguredTargetDependencies(
                env, ctgValue, depValueNames.values(), transitivePackages, transitiveRootCauses);
        if (env.valuesMissing()) {
          return null;
        }
        state.resolveConfiguredTargetDependenciesResult = depValues;
      }

      // Resolve required aspects.
      OrderedSetMultimap<Dependency, ConfiguredAspect> depAspects;
      if (state.resolveAspectDependenciesResult != null) {
        depAspects = state.resolveAspectDependenciesResult;
      } else {
        depAspects =
            AspectResolver.resolveAspectDependencies(
                env, depValues, depValueNames.values(), transitivePackages);
        if (env.valuesMissing()) {
          return null;
        }
        state.resolveAspectDependenciesResult = depAspects;
      }

      // Merge the dependent configured targets and aspects into a single map.
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> mergeAspectsResult;
      try {
        mergeAspectsResult = AspectResolver.mergeAspects(depValueNames, depValues, depAspects);
      } catch (DuplicateException e) {
        throw new DependencyEvaluationException(
            new ConfiguredValueCreationException(ctgValue, e.getMessage()),
            /*depReportedOwnError=*/ false);
      }
      state.computeDependenciesResult = mergeAspectsResult;
      state.storedEventHandlerFromResolveConfigurations.replayOn(env.getListener());

      // We won't need these anymore.
      state.resolveConfigurationsResult = null;
      state.resolveConfiguredTargetDependenciesResult = null;
      state.resolveAspectDependenciesResult = null;

      return mergeAspectsResult;
    } catch (InterruptedException e) {
      // In practice, this comes from resolveConfigurations: other InterruptedExceptions are
      // declared for Skyframe value retrievals, which don't throw in reality.
      if (!transitiveRootCauses.isEmpty()) {
        // Allow caller to throw, don't prioritize interrupt: we may be error bubbling.
        Thread.currentThread().interrupt();
        return null;
      }
      throw e;
    }
  }

  /**
   * Returns the targets that key the configurable attributes used by this rule.
   *
   * <p>>If the configured targets supplying those providers aren't yet resolved by the dependency
   * resolver, returns null.
   */
  @Nullable
  static ConfigConditions getConfigConditions(
      Environment env,
      TargetAndConfiguration ctgValue,
      @Nullable NestedSetBuilder<Package> transitivePackages,
      @Nullable PlatformInfo platformInfo,
      NestedSetBuilder<Cause> transitiveRootCauses)
      throws ConfiguredValueCreationException, InterruptedException {
    Target target = ctgValue.getTarget();
    if (!(target instanceof Rule)) {
      return ConfigConditions.EMPTY;
    }
    RawAttributeMapper attrs = RawAttributeMapper.of(((Rule) target));
    if (!attrs.has(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE)) {
      return ConfigConditions.EMPTY;
    }

    // Collect the labels of the configured targets we need to resolve.
    List<Label> configLabels =
        attrs.get(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE, BuildType.LABEL_LIST);
    if (configLabels.isEmpty()) {
      return ConfigConditions.EMPTY;
    }

    // Collect the actual deps without a configuration transition (since by definition config
    // conditions evaluate over the current target's configuration). If the dependency is
    // (erroneously) something that needs the null configuration, its analysis will be
    // short-circuited. That error will be reported later.
    ImmutableList.Builder<Dependency> depsBuilder = ImmutableList.builder();
    for (Label configurabilityLabel : configLabels) {
      Dependency configurabilityDependency =
          Dependency.builder()
              .setLabel(configurabilityLabel)
              .setConfiguration(ctgValue.getConfiguration())
              .build();
      depsBuilder.add(configurabilityDependency);
    }

    ImmutableList<Dependency> configConditionDeps = depsBuilder.build();

    Map<SkyKey, ConfiguredTargetAndData> configValues;
    try {
      configValues =
          resolveConfiguredTargetDependencies(
              env, ctgValue, configConditionDeps, transitivePackages, transitiveRootCauses);
      if (configValues == null) {
        return null;
      }
    } catch (DependencyEvaluationException e) {
      // One of the config dependencies doesn't exist, and we need to report that. Unfortunately,
      // there's not enough information to know which configurable attribute has the problem.
      throw new ConfiguredValueCreationException(
          // The precise error is reported by the dependency that failed to load.
          // TODO(gregce): beautify this error: https://github.com/bazelbuild/bazel/issues/11984.
          ctgValue, "errors encountered resolving select() keys for " + target.getLabel());
    }

    ImmutableMap.Builder<Label, ConfiguredTargetAndData> asConfiguredTargets =
        ImmutableMap.builder();
    ImmutableMap.Builder<Label, ConfigMatchingProvider> asConfigConditions = ImmutableMap.builder();

    // Get the configured targets as ConfigMatchingProvider interfaces.
    for (Dependency entry : configConditionDeps) {
      SkyKey baseKey = entry.getConfiguredTargetKey();
      // The code above guarantees that selectKeyTarget is non-null here.
      ConfiguredTargetAndData selectKeyTarget = configValues.get(baseKey);
      asConfiguredTargets.put(entry.getLabel(), selectKeyTarget);
      try {
        asConfigConditions.put(
            entry.getLabel(), ConfigConditions.fromConfiguredTarget(selectKeyTarget, platformInfo));
      } catch (ConfigConditions.InvalidConditionException e) {
        String message =
            String.format(
                    "%s is not a valid select() condition for %s.\n",
                    selectKeyTarget.getTargetLabel(), target.getLabel())
                + String.format(
                    "To inspect the select(), run: bazel query --output=build %s.\n",
                    target.getLabel())
                + "For more help, see https://bazel.build/reference/be/functions#select.\n\n";
        throw new ConfiguredValueCreationException(ctgValue, message);
      }
    }

    return ConfigConditions.create(
        asConfiguredTargets.buildOrThrow(), asConfigConditions.buildOrThrow());
  }

  /**
   * Resolves the targets referenced in depValueNames and returns their {@link
   * ConfiguredTargetAndData} instances.
   *
   * <p>Returns null if not all instances are available yet.
   */
  @Nullable
  private static Map<SkyKey, ConfiguredTargetAndData> resolveConfiguredTargetDependencies(
      Environment env,
      TargetAndConfiguration ctgValue,
      Collection<Dependency> deps,
      @Nullable NestedSetBuilder<Package> transitivePackages,
      NestedSetBuilder<Cause> transitiveRootCauses)
      throws DependencyEvaluationException, InterruptedException {
    boolean missedValues = env.valuesMissing();
    ConfiguredValueCreationException rootError = null;
    DetailedExitCode detailedExitCode = null;
    // Naively we would like to just fetch all requested ConfiguredTargets, together with their
    // Packages. However, some ConfiguredTargets are AliasConfiguredTargets, which means that their
    // associated Targets (and therefore associated Packages) don't correspond to their own Labels.
    // We don't know the associated Package until we fetch the ConfiguredTarget. Therefore, we have
    // to do a potential second pass, in which we fetch all the Packages for AliasConfiguredTargets.
    ImmutableSet<SkyKey> packageKeys =
        ImmutableSet.copyOf(
            Iterables.transform(
                deps, input -> PackageValue.key(input.getLabel().getPackageIdentifier())));
    Iterable<SkyKey> depKeys =
        Iterables.concat(
            Iterables.transform(deps, Dependency::getConfiguredTargetKey), packageKeys);
    SkyframeLookupResult depValuesOrExceptions = env.getValuesAndExceptions(depKeys);
    boolean depValuesMissingForDebugging = env.valuesMissing();
    Map<SkyKey, ConfiguredTargetAndData> result = Maps.newHashMapWithExpectedSize(deps.size());
    Set<SkyKey> aliasPackagesToFetch = new HashSet<>();
    List<Dependency> aliasDepsToRedo = new ArrayList<>();
    SkyframeLookupResult aliasPackageValues = null;
    Collection<Dependency> depsToProcess = deps;
    for (int i = 0; i < 2; i++) {
      for (Dependency dep : depsToProcess) {
        SkyKey key = dep.getConfiguredTargetKey();
        ConfiguredTargetValue depValue;
        try {
          depValue =
              (ConfiguredTargetValue)
                  depValuesOrExceptions.getOrThrow(key, ConfiguredValueCreationException.class);
        } catch (ConfiguredValueCreationException e) {
          transitiveRootCauses.addTransitive(e.getRootCauses());
          detailedExitCode =
              DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
                  e.getDetailedExitCode(), detailedExitCode);
          if (e.getDetailedExitCode().equals(detailedExitCode)) {
            rootError = e;
          }
          continue;
        }
        if (depValue == null) {
          if (!depValuesMissingForDebugging) {
            BugReport.logUnexpected(
                "Unexpected exception: dep %s had null value, even though there were no values"
                    + " missing in the initial fetch. That means it had an unexpected exception"
                    + " type (not ConfiguredValueCreationException)",
                dep);
            depValuesMissingForDebugging = true;
          }
          missedValues = true;
          continue;
        }

        ConfiguredTarget depCt = depValue.getConfiguredTarget();
        Label depLabel = depCt.getLabel();
        SkyKey packageKey = PackageValue.key(depLabel.getPackageIdentifier());
        PackageValue pkgValue;
        if (i == 0) {
          if (!packageKeys.contains(packageKey)) {
            aliasPackagesToFetch.add(packageKey);
            aliasDepsToRedo.add(dep);
            continue;
          } else {
            pkgValue = (PackageValue) depValuesOrExceptions.get(packageKey);
            if (pkgValue == null) {
              // In a race, the getValuesAndExceptions call above may have retrieved the package
              // before it was done but the configured target after it was done. Since
              // SkyFunctionEnvironment may cache absent values, re-requesting it on this evaluation
              // may be useless, just treat it as missing.
              missedValues = true;
              continue;
            }
          }
        } else {
          // We were doing AliasConfiguredTarget mop-up.
          pkgValue = (PackageValue) aliasPackageValues.get(packageKey);
          if (pkgValue == null) {
            // This is unexpected: on the second iteration, all packages should be present, since
            // the configured targets that depend on them are present. But since that is not a
            // guarantee Skyframe makes, we tolerate their absence.
            missedValues = true;
            continue;
          }
        }

        try {
          BuildConfigurationValue depConfiguration = dep.getConfiguration();
          BuildConfigurationKey depKey = depValue.getConfiguredTarget().getConfigurationKey();
          if (depKey != null && !depKey.equals(depConfiguration.getKey())) {
            depConfiguration = (BuildConfigurationValue) env.getValue(depKey);
          }
          result.put(
              key,
              new ConfiguredTargetAndData(
                  depValue.getConfiguredTarget(),
                  pkgValue.getPackage().getTarget(depLabel.getName()),
                  depConfiguration,
                  dep.getTransitionKeys()));
        } catch (NoSuchTargetException e) {
          throw new IllegalStateException("Target already verified for " + dep, e);
        }
        if (transitivePackages != null) {
          transitivePackages.addTransitive(
              Preconditions.checkNotNull(depValue.getTransitivePackages()));
        }
      }

      if (aliasDepsToRedo.isEmpty()) {
        break;
      }
      aliasPackageValues = env.getValuesAndExceptions(aliasPackagesToFetch);
      depsToProcess = aliasDepsToRedo;
    }

    if (rootError != null) {
      throw new DependencyEvaluationException(
          new ConfiguredValueCreationException(
              ctgValue, rootError.getMessage(), transitiveRootCauses.build(), detailedExitCode),
          /*depReportedOwnError=*/ true);
    }
    return missedValues ? null : result;
  }

  @Override
  public String extractTag(SkyKey skyKey) {
    return Label.print(((ConfiguredTargetKey) skyKey.argument()).getLabel());
  }

  @SuppressWarnings("LenientFormatStringValidation")
  @Nullable
  private static ConfiguredTargetValue createConfiguredTarget(
      SkyframeBuildView view,
      Environment env,
      TargetAndConfiguration ctgValue,
      ConfiguredTargetKey configuredTargetKey,
      OrderedSetMultimap<DependencyKind, ConfiguredTargetAndData> depValueMap,
      ConfigConditions configConditions,
      @Nullable ToolchainCollection<ResolvedToolchainContext> toolchainContexts,
      ExecGroupCollection.Builder execGroupCollectionBuilder,
      @Nullable NestedSetBuilder<Package> transitivePackagesBuilder)
      throws ConfiguredValueCreationException, InterruptedException {
    Target target = ctgValue.getTarget();
    BuildConfigurationValue configuration = ctgValue.getConfiguration();

    // Should be successfully evaluated and cached from the loading phase.
    StarlarkBuiltinsValue starlarkBuiltinsValue =
        (StarlarkBuiltinsValue) env.getValue(StarlarkBuiltinsValue.key());
    if (starlarkBuiltinsValue == null) {
      return null;
    }

    NestedSet<Package> transitivePackages =
        transitivePackagesBuilder == null ? null : transitivePackagesBuilder.build();
    StoredEventHandler events = new StoredEventHandler();
    CachingAnalysisEnvironment analysisEnvironment =
        view.createAnalysisEnvironment(
            configuredTargetKey, events, env, configuration, starlarkBuiltinsValue);

    Preconditions.checkNotNull(depValueMap);
    ConfiguredTarget configuredTarget;
    try {
      configuredTarget =
          view.createConfiguredTarget(
              target,
              configuration,
              analysisEnvironment,
              configuredTargetKey,
              depValueMap,
              configConditions,
              toolchainContexts,
              transitivePackages,
              execGroupCollectionBuilder);
    } catch (MissingDepException e) {
      Preconditions.checkState(env.valuesMissing(), e.getMessage());
      return null;
    } catch (ActionConflictException e) {
      e.reportTo(env.getListener());
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    } catch (InvalidExecGroupException e) {
      throw new ConfiguredValueCreationException(ctgValue, e.getMessage());
    } catch (AnalysisFailurePropagationException e) {
      throw new ConfiguredValueCreationException(
          ctgValue, e.getMessage(), /* rootCauses = */ null, e.getDetailedExitCode());
    }

    events.replayOn(env.getListener());
    if (events.hasErrors()) {
      analysisEnvironment.disable(target);
      NestedSet<Cause> rootCauses =
          NestedSetBuilder.wrap(
              Order.STABLE_ORDER,
              events.getEvents().stream()
                  .filter((event) -> event.getKind() == EventKind.ERROR)
                  .map(
                      (event) ->
                          new AnalysisFailedCause(
                              target.getLabel(),
                              configuration == null
                                  ? null
                                  : configuration.getEventId().getConfiguration(),
                              createDetailedExitCode(event.getMessage())))
                  .collect(Collectors.toList()));
      throw new ConfiguredValueCreationException(
          ctgValue, "Analysis of target '" + target.getLabel() + "' failed", rootCauses, null);
    }
    Preconditions.checkState(
        !analysisEnvironment.hasErrors(), "Analysis environment hasError() but no errors reported");
    if (env.valuesMissing()) {
      return null;
    }

    analysisEnvironment.disable(target);
    Preconditions.checkNotNull(configuredTarget, target);

    if (configuredTarget instanceof RuleConfiguredTarget) {
      RuleConfiguredTarget ruleConfiguredTarget = (RuleConfiguredTarget) configuredTarget;
      return new RuleConfiguredTargetValue(ruleConfiguredTarget, transitivePackages);
    } else {
      // Expected 4 args, but got 3.
      Preconditions.checkState(
          analysisEnvironment.getRegisteredActions().isEmpty(),
          "Non-rule can't have actions: %s %s %s",
          configuredTargetKey,
          analysisEnvironment.getRegisteredActions(),
          configuredTarget);
      return new NonRuleConfiguredTargetValue(configuredTarget, transitivePackages);
    }
  }

  private static DetailedExitCode createDetailedExitCode(String message) {
    return DetailedExitCode.of(
        FailureDetail.newBuilder()
            .setMessage(message)
            .setAnalysis(Analysis.newBuilder().setCode(Code.CONFIGURED_VALUE_CREATION_FAILED))
            .build());
  }

  static DetailedExitCode getPrioritizedDetailedExitCode(NestedSet<Cause> causes) {
    DetailedExitCode prioritizedDetailedExitCode = null;
    for (Cause c : causes.toList()) {
      prioritizedDetailedExitCode =
          DetailedExitCodeComparator.chooseMoreImportantWithFirstIfTie(
              prioritizedDetailedExitCode, c.getDetailedExitCode());
    }
    return prioritizedDetailedExitCode;
  }

  /**
   * {@link ConfiguredTargetFunction#compute} exception that has already had its error reported to
   * the user. Callers (like {@link com.google.devtools.build.lib.buildtool.BuildTool}) won't also
   * report the error.
   */
  private static class ReportedException extends SkyFunctionException {
    ReportedException(ConfiguredValueCreationException e) {
      super(withoutMessage(e), Transience.PERSISTENT);
    }

    /** Clones a {@link ConfiguredValueCreationException} with its {@code message} field removed. */
    private static ConfiguredValueCreationException withoutMessage(
        ConfiguredValueCreationException orig) {
      return new ConfiguredValueCreationException(
          orig.getLocation(),
          "",
          /*label=*/ null,
          orig.getConfiguration(),
          orig.getRootCauses(),
          orig.getDetailedExitCode());
    }
  }

  /**
   * {@link ConfiguredTargetFunction#compute} exception that has not had its error reported to the
   * user. Callers (like {@link com.google.devtools.build.lib.buildtool.BuildTool}) are responsible
   * for reporting the error.
   */
  private static class UnreportedException extends SkyFunctionException {
    UnreportedException(ConfiguredValueCreationException e) {
      super(e, Transience.PERSISTENT);
    }
  }

  static class NoMatchingPlatformException extends ToolchainException {
    NoMatchingPlatformException(NoMatchingPlatformData error) {
      super(error.formatError());
    }

    @Override
    protected FailureDetails.Toolchain.Code getDetailedCode() {
      return FailureDetails.Toolchain.Code.NO_MATCHING_EXECUTION_PLATFORM;
    }
  }
}
