# ---------------------------------------------------------------------------
# Instrumentation-pair keeps, narrowed from whole namespaces to the exact
# classes the androidTest APK references.
#
# Mechanism (see proguard-rules.pro for the full history): the androidTest APK
# is compiled against this build's R8 mapping, so it only breaks where R8
# REMOVED a class the test code (or a test framework running in the test APK:
# AndroidJUnitRunner, Espresso, the Compose test rules, coroutines-test,
# room-testing) references. Keeping exactly that reference set satisfies the
# pair while letting R8 shrink and obfuscate everything else in these
# namespaces - the broad "-keep class kotlin.** / kotlinx.coroutines.**"
# rules this replaces kept the whole stdlib and coroutines unshrunk, which
# cost the x86_64 APK ~26 MB over v1.1.17 (43,247,995 vs 16,936,470 bytes).
#
# How this list was derived (regenerate when the test sources or the
# androidTest dependency versions change):
#   1. Parse the constant pools of every androidTestImplementation library
#      (AARs/JARs at the exact versions pinned in gradle/libs.versions.toml)
#      and collect every referenced class under the narrowed namespaces.
#   2. Add the classes the androidTest SOURCES reference that no library
#      mentions (e.g. kotlinx.coroutines.flow.FlowKt for `Flow.first`).
#   3. Keep each class with all members: a member only the suite calls
#      (a facade function the app never uses) would otherwise be stripped
#      from a surviving class - NoSuchMethodError, not NoClassDefFoundError.
# Classes in these namespaces that the app itself references survive R8
# renamed anyway, so keeping them costs nothing; the size win comes from the
# unreferenced remainder becoming shrinkable again.
#
# Every failure this file fails to prevent surfaces as NoClassDefFoundError
# (removed class) or NoSuchMethodError (removed member) in the release-test
# suite - add the missing class from the stack trace, do not widen back to
# the namespace.
#
# Field history: release-test run 34857186998 (API 30 leg) failed 23 tests
# on NoClassDefFoundError: kotlinx.coroutines.DelayWithTimeoutDiagnostics.
# The class is referenced by kotlinx-coroutines-test, whose JAR lives under
# the gradle cache's "kotlinx-coroutines-test-jvm" module dir - the first
# extraction scanned the metadata-only "kotlinx-coroutines-test" dir and so
# missed every reference from that library (85 classes). Always scan the
# -jvm / -android variant of every module, which is where the bytecode
# actually lives.
#
# Run 34860029235 (API 35 leg) then failed its last 3 tests - all
# MigrationInstrumentedTest, all NoClassDefFoundError:
# kotlin.coroutines.jvm.internal.Boxing. That class is referenced by the
# COMPILER OUTPUT of the test sources themselves (the suspend state
# machine's primitive boxing), not by any library, so the constant-pool
# extraction could not see it - the compiled test classes do not exist
# locally. Boxing is added by hand below.
# ---------------------------------------------------------------------------
-keep class kotlin.annotation.AnnotationRetention { *; }
-keep class kotlin.annotation.Retention { *; }
-keep class kotlin.AssertionError { *; }
-keep class kotlin._Assertions { *; }
-keep class kotlin.collections.AbstractIterator { *; }
-keep class kotlin.collections.ArrayDeque { *; }
-keep class kotlin.collections.ArrayList { *; }
-keep class kotlin.collections.ArraysKt { *; }
-keep class kotlin.collections.CollectionsKt { *; }
-keep class kotlin.collections.HashSet { *; }
-keep class kotlin.collections.MapsKt { *; }
-keep class kotlin.collections.SetsKt { *; }
-keep class kotlin.comparisons.ComparisonsKt { *; }
-keep class kotlin.comparisons.ComparisonsKt__ComparisonsKt { *; }
-keep class kotlin.coroutines.AbstractCoroutineContextElement { *; }
-keep class kotlin.coroutines.Continuation { *; }
-keep class kotlin.coroutines.ContinuationInterceptor { *; }
-keep class kotlin.coroutines.ContinuationInterceptor$DefaultImpls { *; }
-keep class kotlin.coroutines.ContinuationInterceptor$Key { *; }
-keep class kotlin.coroutines.ContinuationKt { *; }
-keep class kotlin.coroutines.CoroutineContext { *; }
-keep class kotlin.coroutines.CoroutineContext$Element { *; }
-keep class kotlin.coroutines.CoroutineContext$Element$DefaultImpls { *; }
-keep class kotlin.coroutines.CoroutineContext$Key { *; }
-keep class kotlin.coroutines.EmptyCoroutineContext { *; }
-keep class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }
-keep class kotlin.coroutines.jvm.internal.Boxing { *; }
-keep class kotlin.coroutines.jvm.internal.ContinuationImpl { *; }
-keep class kotlin.coroutines.jvm.internal.DebugMetadata { *; }
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt { *; }
-keep class kotlin.coroutines.jvm.internal.SpillingKt { *; }
-keep class kotlin.coroutines.jvm.internal.SuspendFunction { *; }
-keep class kotlin.coroutines.jvm.internal.SuspendLambda { *; }
-keep class kotlin.Deprecated { *; }
-keep class kotlin.DeprecationLevel { *; }
-keep class kotlin.enums.EnumEntries { *; }
-keep class kotlin.enums.EnumEntriesKt { *; }
-keep class kotlin.Exception { *; }
-keep class kotlin.ExceptionsKt { *; }
-keep class kotlin.ExtensionFunctionType { *; }
-keep class kotlin.Function { *; }
-keep class kotlin.Function0 { *; }
-keep class kotlin.Function1 { *; }
-keep class kotlin.Function2 { *; }
-keep class kotlin.Function3 { *; }
-keep class kotlin.IllegalStateException { *; }
-keep class kotlin.io.CloseableKt { *; }
-keep class kotlin.jdk7.AutoCloseableKt { *; }
-keep class kotlin.jvm.functions.Function0 { *; }
-keep class kotlin.jvm.functions.Function1 { *; }
-keep class kotlin.jvm.functions.Function2 { *; }
-keep class kotlin.jvm.functions.Function3 { *; }
-keep class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep class kotlin.jvm.internal.FunctionReferenceImpl { *; }
-keep class kotlin.jvm.internal.InlineMarker { *; }
-keep class kotlin.jvm.internal.Intrinsics { *; }
-keep class kotlin.jvm.internal.Intrinsics$Kotlin { *; }
-keep class kotlin.jvm.internal.Lambda { *; }
-keep class kotlin.jvm.internal.markers.KMappedMarker { *; }
-keep class kotlin.jvm.internal.PropertyReference1Impl { *; }
-keep class kotlin.jvm.internal.Ref { *; }
-keep class kotlin.jvm.internal.Ref$BooleanRef { *; }
-keep class kotlin.jvm.internal.Ref$FloatRef { *; }
-keep class kotlin.jvm.internal.Ref$IntRef { *; }
-keep class kotlin.jvm.internal.Ref$LongRef { *; }
-keep class kotlin.jvm.internal.Ref$ObjectRef { *; }
-keep class kotlin.jvm.internal.Reflection { *; }
-keep class kotlin.jvm.internal.SourceDebugExtension { *; }
-keep class kotlin.jvm.internal.StringCompanionObject { *; }
-keep class kotlin.jvm.JvmClassMappingKt { *; }
-keep class kotlin.jvm.JvmDefaultWithCompatibility { *; }
-keep class kotlin.jvm.JvmField { *; }
-keep class kotlin.jvm.JvmInline { *; }
-keep class kotlin.jvm.JvmName { *; }
-keep class kotlin.jvm.JvmOverloads { *; }
-keep class kotlin.jvm.JvmStatic { *; }
-keep class kotlin.KotlinNothingValueException { *; }
-keep class kotlin.Lazy { *; }
-keep class kotlin.LazyKt { *; }
-keep class kotlin.math.MathKt { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.NoWhenBranchMatchedException { *; }
-keep class kotlin.Pair { *; }
-keep class kotlin.ParameterName { *; }
-keep class kotlin.PublishedApi { *; }
-keep class kotlin.ranges.LongRange { *; }
-keep class kotlin.ranges.RangesKt { *; }
-keep class kotlin.reflect.KClass { *; }
-keep class kotlin.reflect.KClasses { *; }
-keep class kotlin.reflect.KProperty { *; }
-keep class kotlin.ReplaceWith { *; }
-keep class kotlin.RequiresOptIn { *; }
-keep class kotlin.Result { *; }
-keep class kotlin.Result$Companion { *; }
-keep class kotlin.ResultKt { *; }
-keep class kotlin.RuntimeException { *; }
-keep class kotlin.sequences.Sequence { *; }
-keep class kotlin.sequences.SequencesKt { *; }
# MatchResult as well as Regex, and for a reason Regex alone does not cover: the
# suite reads a match's captures through this INTERFACE (MatchResult.getGroupValues),
# and R8 removed that member from it because every reference in the app itself had
# been devirtualized onto the concrete impl - so the app kept working while the
# instrumentation APK died with "NoSuchMethodError: No interface method
# getGroupValues()" (run 35106576845, a single line in the LINK probe). Keeping the
# class with all members is this file's rule for a surviving class; the probe no
# longer needs it, and this is what stops the next Regex user in androidTest from
# finding out the same way. If the list is ever regenerated, MatchResult must stay:
# a derivation that walks referenced classes sees Regex, not the interface a match
# is read through.
-keep class kotlin.text.MatchResult { *; }
-keep class kotlin.text.Regex { *; }
-keep class kotlin.text.StringBuilder { *; }
-keep class kotlin.text.StringsKt { *; }
-keep class kotlin.time.AbstractLongTimeSource { *; }
-keep class kotlin.time.Duration { *; }
-keep class kotlin.time.Duration$Companion { *; }
-keep class kotlin.time.DurationKt { *; }
-keep class kotlin.time.DurationUnit { *; }
-keep class kotlin.time.TimeSource { *; }
-keep class kotlin.time.TimeSource$WithComparableMarks { *; }
-keep class kotlin.Unit { *; }
-keep class kotlinx.coroutines.AbstractCoroutine { *; }
-keep class kotlinx.coroutines.BuildersKt { *; }
-keep class kotlinx.coroutines.CancellableContinuation { *; }
-keep class kotlinx.coroutines.CancellableContinuationImpl { *; }
-keep class kotlinx.coroutines.CancellableContinuationKt { *; }
-keep class kotlinx.coroutines.channels.BufferOverflow { *; }
-keep class kotlinx.coroutines.channels.Channel { *; }
-keep class kotlinx.coroutines.channels.ChannelKt { *; }
-keep class kotlinx.coroutines.CompletableJob { *; }
-keep class kotlinx.coroutines.CoroutineDispatcher { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler$Key { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandlerKt { *; }
-keep class kotlinx.coroutines.CoroutineName { *; }
-keep class kotlinx.coroutines.CoroutineScope { *; }
-keep class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep class kotlinx.coroutines.CoroutineStart { *; }
-keep class kotlinx.coroutines.debug.internal.DebugProbesImpl { *; }
-keep class kotlinx.coroutines.DebugKt { *; }
-keep class kotlinx.coroutines.DefaultExecutorKt { *; }
-keep class kotlinx.coroutines.Deferred { *; }
-keep class kotlinx.coroutines.Delay { *; }
-keep class kotlinx.coroutines.DelayKt { *; }
-keep class kotlinx.coroutines.DelayWithTimeoutDiagnostics { *; }
-keep class kotlinx.coroutines.Dispatchers { *; }
-keep class kotlinx.coroutines.DisposableHandle { *; }
-keep class kotlinx.coroutines.ExecutorsKt { *; }
-keep class kotlinx.coroutines.ExperimentalCoroutinesApi { *; }
-keep class kotlinx.coroutines.flow.Flow { *; }
-keep class kotlinx.coroutines.flow.FlowKt { *; }
-keep class kotlinx.coroutines.internal.CoroutineExceptionHandlerImplKt { *; }
-keep class kotlinx.coroutines.internal.ExceptionSuccessfullyProcessed { *; }
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.MainDispatchersKt { *; }
-keep class kotlinx.coroutines.internal.MissingMainCoroutineDispatcherFactory { *; }
-keep class kotlinx.coroutines.internal.StackTraceRecoveryKt { *; }
-keep class kotlinx.coroutines.internal.SynchronizedObject { *; }
-keep class kotlinx.coroutines.internal.ThreadSafeHeap { *; }
-keep class kotlinx.coroutines.internal.ThreadSafeHeapNode { *; }
-keep class kotlinx.coroutines.Job { *; }
-keep class kotlinx.coroutines.Job$DefaultImpls { *; }
-keep class kotlinx.coroutines.Job$Key { *; }
-keep class kotlinx.coroutines.JobImpl { *; }
-keep class kotlinx.coroutines.JobKt { *; }
-keep class kotlinx.coroutines.JobSupport { *; }
-keep class kotlinx.coroutines.MainCoroutineDispatcher { *; }
-keep class kotlinx.coroutines.Runnable { *; }
-keep class kotlinx.coroutines.selects.OnTimeoutKt { *; }
-keep class kotlinx.coroutines.selects.SelectBuilder { *; }
-keep class kotlinx.coroutines.selects.SelectClause0 { *; }
-keep class kotlinx.coroutines.selects.SelectClause1 { *; }
-keep class kotlinx.coroutines.selects.SelectImplementation { *; }
-keep class kotlinx.coroutines.TimeoutCancellationException { *; }
-keep class kotlinx.coroutines.TimeoutKt { *; }
-keep class kotlinx.coroutines.YieldContext { *; }
-keep class kotlinx.coroutines.YieldContext$Key { *; }
-keep class kotlinx.coroutines.YieldKt { *; }

# The app classes the androidTest pair reaches into: UbuntuE2eVerificationTest
# reads graph.runtime / graph.manager, getters nothing in the app calls (the
# app reads the fields directly), so R8 stripped them while the class itself
# survived renamed - NoSuchMethodError, not NoClassDefFoundError (android-ubuntu-e2e
# run 34854865510: 11 failures, all getRuntime()Ldev/eclipse/ssh/linux/ProotRuntime;
# or getManager() on the obfuscated LinuxUserspaceGraph). The test APK is
# compiled against the R8 mapping, so the renamed return types in the kept
# descriptors line up on both sides.
#
# E2E run 34869834710 peeled the next layer: with the graph's getters kept,
# the suite's own calls into the objects they return started resolving and
# failing. sessionArgv() is a trivial ProotRuntime facade (it only forwards
# to commandArgv) that R8 inlined at every app call site and then removed -
# 9 failures - and LinuxUserspaceManager's state getter and refreshHealth()
# suspend are members only the suite calls - 2 failures. Whole-class keeps,
# same reasoning as the graph keep above.
-keep class dev.eclipse.ssh.di.LinuxUserspaceGraph { *; }
-keep class dev.eclipse.ssh.linux.ProotRuntime { *; }
-keep class dev.eclipse.ssh.linux.LinuxUserspaceManager { *; }
#
# E2E run 35035691404 peeled the layer under those: the value types the
# suite's own helpers consume. ProotRuntime.runCommand returns
# ProotCommandResult, whose exitCode getter and outputText() the app never
# calls (the app's setup pipeline checks the result of the whole session
# helper, not the result object) - R8 kept the class renamed (t33) but
# stripped the members, so sessionSucceeds() died with NoSuchMethodError:
# No virtual method getExitCode()I in class Lt33 - 11 failures across every
# test that runs a session command. HealthReport is kept defensively: its
# healthy/describe() members are app-referenced today (so R8 would keep
# them anyway), but the suite reads the object through the kept manager
# facade and a future app refactor dropping those members would otherwise
# break the pair silently - the derivation here is "what the suite
# touches", not "what survives today".
-keep class dev.eclipse.ssh.linux.ProotCommandResult { *; }
-keep class dev.eclipse.ssh.linux.HealthReport { *; }

# ---------------------------------------------------------------------------
# Stage 2: androidx.compose, narrowed from the whole-namespace keep in
# proguard-rules.pro. Same derivation as stage 1 (see the header): the
# constant pools of the compose test libraries (ui-test, ui-test-android,
# ui-test-junit4, ui-test-junit4-android) plus the espresso/runner stack,
# all at the versions pinned in gradle/libs.versions.toml. The facades the
# androidTest sources call directly (ActionsKt, FindersKt, FiltersKt,
# AssertionsKt, AndroidComposeTestRule_androidKt) are all referenced by
# those libraries too, so no hand additions were needed this time - unlike
# stage 1's Boxing.
# ---------------------------------------------------------------------------
-keep class androidx.compose.runtime.Applier { *; }
-keep class androidx.compose.runtime.Composable { *; }
-keep class androidx.compose.runtime.ComposableInferredTarget { *; }
-keep class androidx.compose.runtime.ComposableTarget { *; }
-keep class androidx.compose.runtime.ComposablesKt { *; }
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.Composer$Companion { *; }
-keep class androidx.compose.runtime.ComposerKt { *; }
-keep class androidx.compose.runtime.CompositionContext { *; }
-keep class androidx.compose.runtime.CompositionLocal { *; }
-keep class androidx.compose.runtime.CompositionLocalKt { *; }
-keep class androidx.compose.runtime.CompositionLocalMap { *; }
-keep class androidx.compose.runtime.DisposableEffectResult { *; }
-keep class androidx.compose.runtime.DisposableEffectScope { *; }
-keep class androidx.compose.runtime.EffectsKt { *; }
-keep class androidx.compose.runtime.MonotonicFrameClock { *; }
-keep class androidx.compose.runtime.MonotonicFrameClock$DefaultImpls { *; }
-keep class androidx.compose.runtime.MutableState { *; }
-keep class androidx.compose.runtime.ProvidableCompositionLocal { *; }
-keep class androidx.compose.runtime.ProvidedValue { *; }
-keep class androidx.compose.runtime.RecomposeScopeImplKt { *; }
-keep class androidx.compose.runtime.Recomposer { *; }
-keep class androidx.compose.runtime.ScopeUpdateScope { *; }
-keep class androidx.compose.runtime.SnapshotMutationPolicy { *; }
-keep class androidx.compose.runtime.SnapshotStateKt { *; }
-keep class androidx.compose.runtime.Stable { *; }
-keep class androidx.compose.runtime.State { *; }
-keep class androidx.compose.runtime.Updater { *; }
-keep class androidx.compose.runtime.internal.ComposableLambda { *; }
-keep class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
-keep class androidx.compose.runtime.internal.StabilityInferred { *; }
-keep class androidx.compose.runtime.saveable.SaveableStateRegistry { *; }
-keep class androidx.compose.runtime.saveable.SaveableStateRegistry$Entry { *; }
-keep class androidx.compose.runtime.saveable.SaveableStateRegistryKt { *; }
-keep class androidx.compose.runtime.snapshots.Snapshot { *; }
-keep class androidx.compose.runtime.snapshots.Snapshot$Companion { *; }
-keep class androidx.compose.ui.ComposedModifierKt { *; }
-keep class androidx.compose.ui.Modifier { *; }
-keep class androidx.compose.ui.Modifier$Companion { *; }
-keep class androidx.compose.ui.geometry.Offset { *; }
-keep class androidx.compose.ui.geometry.Offset$Companion { *; }
-keep class androidx.compose.ui.geometry.OffsetKt { *; }
-keep class androidx.compose.ui.geometry.Rect { *; }
-keep class androidx.compose.ui.geometry.RectKt { *; }
-keep class androidx.compose.ui.geometry.Size { *; }
-keep class androidx.compose.ui.graphics.AndroidImageBitmap_androidKt { *; }
-keep class androidx.compose.ui.graphics.ImageBitmap { *; }
-keep class androidx.compose.ui.input.key.Key { *; }
-keep class androidx.compose.ui.input.key.Key$Companion { *; }
-keep class androidx.compose.ui.input.key.KeyEvent { *; }
-keep class androidx.compose.ui.input.key.KeyEvent_androidKt { *; }
-keep class androidx.compose.ui.input.key.Key_androidKt { *; }
-keep class androidx.compose.ui.input.pointer.util.VelocityTracker { *; }
-keep class androidx.compose.ui.input.pointer.util.VelocityTrackerKt { *; }
-keep class androidx.compose.ui.layout.AlignmentLine { *; }
-keep class androidx.compose.ui.layout.LayoutCoordinates { *; }
-keep class androidx.compose.ui.layout.LayoutCoordinatesKt { *; }
-keep class androidx.compose.ui.layout.LayoutInfo { *; }
-keep class androidx.compose.ui.layout.LayoutModifierKt { *; }
-keep class androidx.compose.ui.layout.Measurable { *; }
-keep class androidx.compose.ui.layout.MeasurePolicy { *; }
-keep class androidx.compose.ui.layout.MeasureResult { *; }
-keep class androidx.compose.ui.layout.MeasureScope { *; }
-keep class androidx.compose.ui.layout.Placeable { *; }
-keep class androidx.compose.ui.layout.Placeable$PlacementScope { *; }
-keep class androidx.compose.ui.layout.SubcomposeLayoutKt { *; }
-keep class androidx.compose.ui.layout.SubcomposeMeasureScope { *; }
-keep class androidx.compose.ui.node.ComposeUiNode { *; }
-keep class androidx.compose.ui.node.ComposeUiNode$Companion { *; }
-keep class androidx.compose.ui.node.RootForTest { *; }
-keep class androidx.compose.ui.platform.AbstractComposeView { *; }
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-keep class androidx.compose.ui.platform.CompositionLocalsKt { *; }
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy { *; }
-keep class androidx.compose.ui.platform.InfiniteAnimationPolicy$DefaultImpls { *; }
-keep class androidx.compose.ui.platform.PlatformTextInputInterceptor { *; }
-keep class androidx.compose.ui.platform.PlatformTextInputMethodRequest { *; }
-keep class androidx.compose.ui.platform.PlatformTextInputModifierNodeKt { *; }
-keep class androidx.compose.ui.platform.PlatformTextInputSession { *; }
-keep class androidx.compose.ui.platform.ViewConfiguration { *; }
-keep class androidx.compose.ui.platform.ViewRootForTest { *; }
-keep class androidx.compose.ui.platform.ViewRootForTest$Companion { *; }
-keep class androidx.compose.ui.platform.WindowRecomposerFactory { *; }
-keep class androidx.compose.ui.platform.WindowRecomposerPolicy { *; }
-keep class androidx.compose.ui.semantics.AccessibilityAction { *; }
-keep class androidx.compose.ui.semantics.CustomAccessibilityAction { *; }
-keep class androidx.compose.ui.semantics.ProgressBarRangeInfo { *; }
-keep class androidx.compose.ui.semantics.ScrollAxisRange { *; }
-keep class androidx.compose.ui.semantics.SemanticsActions { *; }
-keep class androidx.compose.ui.semantics.SemanticsConfiguration { *; }
-keep class androidx.compose.ui.semantics.SemanticsConfigurationKt { *; }
-keep class androidx.compose.ui.semantics.SemanticsNode { *; }
-keep class androidx.compose.ui.semantics.SemanticsOwner { *; }
-keep class androidx.compose.ui.semantics.SemanticsOwnerKt { *; }
-keep class androidx.compose.ui.semantics.SemanticsProperties { *; }
-keep class androidx.compose.ui.semantics.SemanticsPropertyKey { *; }
-keep class androidx.compose.ui.state.ToggleableState { *; }
-keep class androidx.compose.ui.test.AbstractMainTestClock { *; }
-keep class androidx.compose.ui.test.AbstractMainTestClock$advanceScheduler$1 { *; }
-keep class androidx.compose.ui.test.AbstractMainTestClock$advanceTimeUntil$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt { *; }
-keep class androidx.compose.ui.test.ActionsKt$performCustomAccessibilityActionWithLabel$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$performFirstLinkClick$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$performScrollToKey$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$performScrollToKey$3 { *; }
-keep class androidx.compose.ui.test.ActionsKt$performScrollToNode$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$performSemanticsAction$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$performSemanticsAction$2 { *; }
-keep class androidx.compose.ui.test.ActionsKt$performSemanticsAction$3 { *; }
-keep class androidx.compose.ui.test.ActionsKt$requireSemantics$msg$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$scrollToIndex$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$scrollToIndex$2 { *; }
-keep class androidx.compose.ui.test.ActionsKt$scrollToNode$1 { *; }
-keep class androidx.compose.ui.test.ActionsKt$scrollToNode$scrollableNode$1 { *; }
-keep class androidx.compose.ui.test.AndroidActions { *; }
-keep class androidx.compose.ui.test.AndroidActions$performClickImpl$1 { *; }
-keep class androidx.compose.ui.test.AndroidActions$tryPerformAccessibilityChecks$1$2$1 { *; }
-keep class androidx.compose.ui.test.AndroidAssertions_androidKt { *; }
-keep class androidx.compose.ui.test.AndroidAssertions_androidKt$checkIsDisplayed$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTest { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$2 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl$awaitIdle$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl$awaitIdle$2 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl$density$2 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl$setContent$3 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl$setContent$3$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidComposeUiTestImpl$withDisposableContent$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$AndroidTestOwner { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$Companion { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$runTest$1$1$1$1$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$withTestCoroutines$1 { *; }
-keep class androidx.compose.ui.test.AndroidComposeUiTestEnvironment$withWindowRecomposer$2$1 { *; }
-keep class androidx.compose.ui.test.AndroidImageHelpers_androidKt { *; }
-keep class androidx.compose.ui.test.AndroidImageHelpers_androidKt$captureToImage$dialogParentNodeMaybe$1 { *; }
-keep class androidx.compose.ui.test.AndroidImageHelpers_androidKt$captureToImage$popupParentMaybe$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$enqueueKeyEvent$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$enqueueMouseEvent$2$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$enqueueMoves$$inlined$sortedBy$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$enqueueRotaryScrollEvent$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$enqueueTouchEvent$$inlined$sortedBy$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$enqueueTouchEvent$5$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$flush$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$flush$1$events$1$1 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$horizontalScrollFactor$2 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher$verticalScrollFactor$2 { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher_androidKt { *; }
-keep class androidx.compose.ui.test.AndroidInputDispatcher_androidKt$createInputDispatcher$2 { *; }
-keep class androidx.compose.ui.test.AndroidOutput_androidKt { *; }
-keep class androidx.compose.ui.test.AndroidSynchronization_androidKt { *; }
-keep class androidx.compose.ui.test.ApplyingContinuationInterceptor { *; }
-keep class androidx.compose.ui.test.ApplyingContinuationInterceptor$SendApplyContinuation { *; }
-keep class androidx.compose.ui.test.AssertionsKt { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertHeightIsAtLeast$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertHeightIsEqualTo$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertLeftPositionInRootIsEqualTo$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertPositionInRootIsEqualTo$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertTopPositionInRootIsEqualTo$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertTouchHeightIsEqualTo$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertTouchWidthIsEqualTo$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertWidthIsAtLeast$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$assertWidthIsEqualTo$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$getAlignmentLinePosition$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$getFirstLinkBounds$1 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$getFirstLinkBounds$2 { *; }
-keep class androidx.compose.ui.test.BoundsAssertionsKt$getUnclippedBoundsInRoot$1 { *; }
-keep class androidx.compose.ui.test.ComposeAccessibilityValidator { *; }
-keep class androidx.compose.ui.test.ComposeIdlingResource { *; }
-keep class androidx.compose.ui.test.ComposeIdlingResource_androidKt { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry$OnRegistrationChangedListener { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry$StateChangeHandler { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry$StateChangeHandler$onViewAttachedToWindow$1 { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry$isSetUp$1 { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry$setupRegistry$1 { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry_androidKt { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry_androidKt$awaitComposeRoots$2$1 { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry_androidKt$awaitComposeRoots$2$listener$1 { *; }
-keep class androidx.compose.ui.test.ComposeRootRegistry_androidKt$waitForComposeRoots$listener$1 { *; }
-keep class androidx.compose.ui.test.ComposeTimeoutException { *; }
-keep class androidx.compose.ui.test.ComposeUiTest { *; }
-keep class androidx.compose.ui.test.ComposeUiTestKt { *; }
-keep class androidx.compose.ui.test.ComposeUiTestKt$waitUntilAtLeastOneExists$1 { *; }
-keep class androidx.compose.ui.test.ComposeUiTestKt$waitUntilNodeCount$1 { *; }
-keep class androidx.compose.ui.test.ComposeUiTest_androidKt { *; }
-keep class androidx.compose.ui.test.ComposeUiTest_androidKt$AndroidComposeUiTestEnvironment$1 { *; }
-keep class androidx.compose.ui.test.ComposeUiTest_androidKt$runAndroidComposeUiTest$$inlined$AndroidComposeUiTestEnvironment$1 { *; }
-keep class androidx.compose.ui.test.ComposeUiTest_androidKt$runAndroidComposeUiTest$1 { *; }
-keep class androidx.compose.ui.test.ComposeUiTest_androidKt$runEmptyComposeUiTest$$inlined$AndroidComposeUiTestEnvironment$default$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$DensityForcedSize$1$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$DensityForcedSize$1$1$3 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$DensityForcedSize$1$1$measurables$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$DensityForcedSize$1$1$measurables$1$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$DensityForcedSize$1$1$measurables$1$1$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$DensityForcedSize$1$1$measurables$1$1$1$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$DensityForcedSize$2 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$size$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSizeKt$size$1$1 { *; }
-keep class androidx.compose.ui.test.DensityForcedSize_androidKt { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride$Companion { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverrideKt { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverrideKt$DeviceConfigurationOverride$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverrideKt$then$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverrideKt$then$1$Override$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$DarkMode$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$FontScale$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$FontWeightAdjustment$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$ForcedSize$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$ForcedSize$1$Override$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$LayoutDirection$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$LayoutDirection$1$WhenMappings { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$Locales$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$Locales$1$Override$1$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$OverriddenConfiguration$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$RoundScreen$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$WindowInsets$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$WindowInsets$1$Override$1$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$WindowInsets$1$Override$1$1$1 { *; }
-keep class androidx.compose.ui.test.DeviceConfigurationOverride_androidKt$WindowInsets$1$Override$2$1 { *; }
-keep class androidx.compose.ui.test.ErrorMessagesKt { *; }
-keep class androidx.compose.ui.test.EspressoLink { *; }
-keep class androidx.compose.ui.test.EspressoLink$registerIdleTransitionCallback$1 { *; }
-keep class androidx.compose.ui.test.EspressoLink_androidKt { *; }
-keep class androidx.compose.ui.test.Expect_jvmKt { *; }
-keep class androidx.compose.ui.test.ExperimentalTestApi { *; }
-keep class androidx.compose.ui.test.FiltersKt { *; }
-keep class androidx.compose.ui.test.FiltersKt$ancestors$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$ancestors$1$iterator$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasAnyAncestor$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasAnyChild$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasAnyDescendant$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasAnySibling$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasContentDescription$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasContentDescription$2 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasContentDescriptionExactly$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasParent$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasText$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasText$2 { *; }
-keep class androidx.compose.ui.test.FiltersKt$hasTextExactly$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$isEnabled$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$isNotEnabled$1 { *; }
-keep class androidx.compose.ui.test.FiltersKt$isRoot$1 { *; }
-keep class androidx.compose.ui.test.FindersKt { *; }
-keep class androidx.compose.ui.test.FrameDeferringContinuationInterceptor { *; }
-keep class androidx.compose.ui.test.FrameDeferringContinuationInterceptor$FrameDeferredContinuation { *; }
-keep class androidx.compose.ui.test.FrameDeferringContinuationInterceptor$TrampolinedTask { *; }
-keep class androidx.compose.ui.test.GestureScope { *; }
-keep class androidx.compose.ui.test.GestureScopeKt { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$advanceEventTime$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$cancel$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$click$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$doubleClick$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$down$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$down$2 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$longClick$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$move$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$moveBy$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$moveBy$2 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$movePointerBy$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$movePointerTo$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$moveTo$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$moveTo$2 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$pinch$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipe$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeDown$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeDown$2 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeLeft$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeLeft$2 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeRight$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeRight$2 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeUp$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeUp$2 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$swipeWithVelocity$1 { *; }
-keep class androidx.compose.ui.test.GestureScopeKt$up$1 { *; }
-keep class androidx.compose.ui.test.GlobalAssertions { *; }
-keep class androidx.compose.ui.test.GlobalAssertionsCollection { *; }
-keep class androidx.compose.ui.test.IdlingResource { *; }
-keep class androidx.compose.ui.test.IdlingResource$DefaultImpls { *; }
-keep class androidx.compose.ui.test.IdlingResourceRegistry { *; }
-keep class androidx.compose.ui.test.IdlingResourceRegistry$getDiagnosticMessageIfBusy$2 { *; }
-keep class androidx.compose.ui.test.IdlingResourceRegistry$getDiagnosticMessageIfBusy$3 { *; }
-keep class androidx.compose.ui.test.IdlingResourceRegistry$isIdleOrEnsurePolling$1$1$1 { *; }
-keep class androidx.compose.ui.test.IdlingStrategy { *; }
-keep class androidx.compose.ui.test.ImpulseVelocityPathFinder { *; }
-keep class androidx.compose.ui.test.ImpulseVelocityPathFinder$FittingResult { *; }
-keep class androidx.compose.ui.test.InjectionScope { *; }
-keep class androidx.compose.ui.test.InjectionScope$DefaultImpls { *; }
-keep class androidx.compose.ui.test.InputDispatcher { *; }
-keep class androidx.compose.ui.test.InputDispatcher$Companion { *; }
-keep class androidx.compose.ui.test.InputDispatcherState { *; }
-keep class androidx.compose.ui.test.InternalTestApi { *; }
-keep class androidx.compose.ui.test.KeyInjectionScope { *; }
-keep class androidx.compose.ui.test.KeyInjectionScope$DefaultImpls { *; }
-keep class androidx.compose.ui.test.KeyInjectionScopeImpl { *; }
-keep class androidx.compose.ui.test.KeyInjectionScopeKt { *; }
-keep class androidx.compose.ui.test.KeyInputHelpersKt { *; }
-keep class androidx.compose.ui.test.KeyInputHelpersKt$performKeyPress$2 { *; }
-keep class androidx.compose.ui.test.KeyInputState { *; }
-keep class androidx.compose.ui.test.LockKeyState { *; }
-keep class androidx.compose.ui.test.LockKeyState$WhenMappings { *; }
-keep class androidx.compose.ui.test.LsqVelocityPathFinder { *; }
-keep class androidx.compose.ui.test.MainTestClock { *; }
-keep class androidx.compose.ui.test.MainTestClock$DefaultImpls { *; }
-keep class androidx.compose.ui.test.MainTestClockImpl { *; }
-keep class androidx.compose.ui.test.MainTestClockImpl$1 { *; }
-keep class androidx.compose.ui.test.MouseButton { *; }
-keep class androidx.compose.ui.test.MouseButton$Companion { *; }
-keep class androidx.compose.ui.test.MouseInjectionScope { *; }
-keep class androidx.compose.ui.test.MouseInjectionScopeImpl { *; }
-keep class androidx.compose.ui.test.MouseInjectionScopeKt { *; }
-keep class androidx.compose.ui.test.MouseInjectionScopeKt$animateMoveTo$1 { *; }
-keep class androidx.compose.ui.test.MouseInputState { *; }
-keep class androidx.compose.ui.test.MultiModalInjectionScope { *; }
-keep class androidx.compose.ui.test.MultiModalInjectionScopeImpl { *; }
-keep class androidx.compose.ui.test.MultiModalInjectionScopeImpl$boundsInRoot$2 { *; }
-keep class androidx.compose.ui.test.MultiModalInjectionScopeImpl$visibleSize$2 { *; }
-keep class androidx.compose.ui.test.OutputKt { *; }
-keep class androidx.compose.ui.test.OutputKt$appendConfigInfo$$inlined$sortedBy$1 { *; }
-keep class androidx.compose.ui.test.PartialGesture { *; }
-keep class androidx.compose.ui.test.PlatformTestContext { *; }
-keep class androidx.compose.ui.test.PlatformTextInputMethodOverrideKt { *; }
-keep class androidx.compose.ui.test.PlatformTextInputMethodOverrideKt$PlatformTextInputMethodTestOverride$1$1 { *; }
-keep class androidx.compose.ui.test.PlatformTextInputMethodOverrideKt$PlatformTextInputMethodTestOverride$1$1$interceptStartInputMethod$1 { *; }
-keep class androidx.compose.ui.test.PlatformTextInputMethodOverrideKt$PlatformTextInputMethodTestOverride$2 { *; }
-keep class androidx.compose.ui.test.ProxyAssertionError { *; }
-keep class androidx.compose.ui.test.RobolectricIdlingStrategy { *; }
-keep class androidx.compose.ui.test.RobolectricIdlingStrategy$runUntilIdle$1 { *; }
-keep class androidx.compose.ui.test.RobolectricIdlingStrategy_androidKt { *; }
-keep class androidx.compose.ui.test.RotaryInjectionScope { *; }
-keep class androidx.compose.ui.test.RotaryInjectionScopeImpl { *; }
-keep class androidx.compose.ui.test.RotaryInputState { *; }
-keep class androidx.compose.ui.test.ScrollWheel { *; }
-keep class androidx.compose.ui.test.ScrollWheel$Companion { *; }
-keep class androidx.compose.ui.test.SelectionResult { *; }
-keep class androidx.compose.ui.test.SelectorsKt { *; }
-keep class androidx.compose.ui.test.SelectorsKt$onAncestors$1 { *; }
-keep class androidx.compose.ui.test.SelectorsKt$onChild$1 { *; }
-keep class androidx.compose.ui.test.SelectorsKt$onChildren$1 { *; }
-keep class androidx.compose.ui.test.SelectorsKt$onParent$1 { *; }
-keep class androidx.compose.ui.test.SelectorsKt$onSibling$1 { *; }
-keep class androidx.compose.ui.test.SelectorsKt$onSiblings$1 { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$Companion { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$Companion$expectValue$1 { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$Companion$expectValue$1$1 { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$Companion$keyIsDefined$1 { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$Companion$keyNotDefined$1 { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$and$1 { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$not$1 { *; }
-keep class androidx.compose.ui.test.SemanticsMatcher$or$1 { *; }
-keep class androidx.compose.ui.test.SemanticsNodeInteraction { *; }
-keep class androidx.compose.ui.test.SemanticsNodeInteraction$fetchSemanticsNodes$1 { *; }
-keep class androidx.compose.ui.test.SemanticsNodeInteraction$getNodesInUnmergedTree$1 { *; }
-keep class androidx.compose.ui.test.SemanticsNodeInteractionCollection { *; }
-keep class androidx.compose.ui.test.SemanticsNodeInteractionCollection$fetchSemanticsNodes$1 { *; }
-keep class androidx.compose.ui.test.SemanticsNodeInteractionsProvider { *; }
-keep class androidx.compose.ui.test.SemanticsNodeInteractionsProvider$DefaultImpls { *; }
-keep class androidx.compose.ui.test.SemanticsSelector { *; }
-keep class androidx.compose.ui.test.SemanticsSelectorKt { *; }
-keep class androidx.compose.ui.test.SemanticsSelectorKt$SemanticsSelector$1 { *; }
-keep class androidx.compose.ui.test.SemanticsSelectorKt$addIndexSelector$1 { *; }
-keep class androidx.compose.ui.test.SemanticsSelectorKt$addLastNodeSelector$1 { *; }
-keep class androidx.compose.ui.test.SemanticsSelectorKt$addSelectionFromSingleNode$1 { *; }
-keep class androidx.compose.ui.test.SemanticsSelectorKt$addSelectorViaMatcher$1 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$InjectRestorationRegistry$1 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$InjectRestorationRegistry$2 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$RestorationRegistry { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$RestorationRegistry$emitChildrenWithRestoredState$1 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$emulateSaveAndRestore$1 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$emulateSaveAndRestore$2 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$emulateSaveAndRestore$3 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$setContent$1 { *; }
-keep class androidx.compose.ui.test.StateRestorationTester$setContent$1$1 { *; }
-keep class androidx.compose.ui.test.TestContext { *; }
-keep class androidx.compose.ui.test.TestContext_androidKt { *; }
-keep class androidx.compose.ui.test.TestMonotonicFrameClock { *; }
-keep class androidx.compose.ui.test.TestMonotonicFrameClock$1 { *; }
-keep class androidx.compose.ui.test.TestMonotonicFrameClock$performFrame$1 { *; }
-keep class androidx.compose.ui.test.TestMonotonicFrameClock$withFrameNanos$2$1$1 { *; }
-keep class androidx.compose.ui.test.TestMonotonicFrameClock$withFrameNanos$2$1$2 { *; }
-keep class androidx.compose.ui.test.TestMonotonicFrameClock_jvmKt { *; }
-keep class androidx.compose.ui.test.TestOwner { *; }
-keep class androidx.compose.ui.test.TestOwnerKt { *; }
-keep class androidx.compose.ui.test.TestOwnerKt$getAllSemanticsNodes$1 { *; }
-keep class androidx.compose.ui.test.TextActionsKt { *; }
-keep class androidx.compose.ui.test.TextActionsKt$getNodeAndFocus$1 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$getNodeAndFocus$2 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$getNodeAndFocus$3 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$getNodeAndFocus$4 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$performImeAction$1 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$performImeAction$2 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$performImeAction$3$1 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$performTextInput$1 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$performTextInputSelection$1 { *; }
-keep class androidx.compose.ui.test.TextActionsKt$performTextReplacement$1 { *; }
-keep class androidx.compose.ui.test.TextActions_jvmKt { *; }
-keep class androidx.compose.ui.test.TouchInjectionScope { *; }
-keep class androidx.compose.ui.test.TouchInjectionScope$DefaultImpls { *; }
-keep class androidx.compose.ui.test.TouchInjectionScopeImpl { *; }
-keep class androidx.compose.ui.test.TouchInjectionScopeKt { *; }
-keep class androidx.compose.ui.test.TouchInjectionScopeKt$multiTouchSwipe$4 { *; }
-keep class androidx.compose.ui.test.TouchInjectionScopeKt$pinch$1 { *; }
-keep class androidx.compose.ui.test.TouchInjectionScopeKt$pinch$2 { *; }
-keep class androidx.compose.ui.test.TouchInjectionScopeKt$swipe$1 { *; }
-keep class androidx.compose.ui.test.TouchInjectionScopeKt$swipeWithVelocity$swipeFunction$1 { *; }
-keep class androidx.compose.ui.test.UncaughtExceptionHandler { *; }
-keep class androidx.compose.ui.test.UtilsKt { *; }
-keep class androidx.compose.ui.test.VelocityPathFinder { *; }
-keep class androidx.compose.ui.test.VelocityPathFinder$Companion { *; }
-keep class androidx.compose.ui.test.VelocityPathFinderKt { *; }
-keep class androidx.compose.ui.test.android.FrameCommitCallbackHelper { *; }
-keep class androidx.compose.ui.test.android.PixelCopyException { *; }
-keep class androidx.compose.ui.test.android.PixelCopyHelper { *; }
-keep class androidx.compose.ui.test.android.WindowCapture_androidKt { *; }
-keep class androidx.compose.ui.test.android.WindowCapture_androidKt$captureRegionToImage$1 { *; }
-keep class androidx.compose.ui.test.android.WindowCapture_androidKt$captureRegionToImage$1$1 { *; }
-keep class androidx.compose.ui.test.android.WindowCapture_androidKt$forceRedraw$1$2 { *; }
-keep class androidx.compose.ui.test.android.WindowCapture_androidKt$forceRedraw$2 { *; }
-keep class androidx.compose.ui.test.internal.DelayPropagatingContinuationInterceptorWrapper { *; }
-keep class androidx.compose.ui.test.internal.JvmDefaultWithCompatibility_jvmKt { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule$AndroidComposeStatement { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1 { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule$apply$1$evaluate$1 { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule$special$$inlined$AndroidComposeUiTestEnvironment$1 { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt$createAndroidComposeRule$1 { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt$createAndroidComposeRule$2 { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt$createEmptyComposeRule$2 { *; }
-keep class androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt$createEmptyComposeRule$4 { *; }
-keep class androidx.compose.ui.test.junit4.ComposeContentTestRule { *; }
-keep class androidx.compose.ui.test.junit4.ComposeContentTestRule$DefaultImpls { *; }
-keep class androidx.compose.ui.test.junit4.ComposeTestRule { *; }
-keep class androidx.compose.ui.test.junit4.ComposeTestRule$DefaultImpls { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$InjectRestorationRegistry$1 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$InjectRestorationRegistry$2 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$RestorationRegistry { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$RestorationRegistry$emitChildrenWithRestoredState$1 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$emulateSavedInstanceStateRestore$1 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$emulateSavedInstanceStateRestore$2 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$emulateSavedInstanceStateRestore$3 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$setContent$1 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$setContent$1$1 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$setContent$1$2$1 { *; }
-keep class androidx.compose.ui.test.junit4.StateRestorationTester$setContent$1$2$1$invoke$$inlined$onDispose$1 { *; }
-keep class androidx.compose.ui.test.junit4.android.ComposeNotIdleException { *; }
-keep class androidx.compose.ui.test.platform.Synchronization_androidKt { *; }
-keep class androidx.compose.ui.test.platform.SynchronizedObject { *; }
-keep class androidx.compose.ui.text.AnnotatedString { *; }
-keep class androidx.compose.ui.text.AnnotatedString$Range { *; }
-keep class androidx.compose.ui.text.LinkAnnotation { *; }
-keep class androidx.compose.ui.text.TextLayoutInput { *; }
-keep class androidx.compose.ui.text.TextLayoutResult { *; }
-keep class androidx.compose.ui.text.TextRange { *; }
-keep class androidx.compose.ui.text.font.FontFamily { *; }
-keep class androidx.compose.ui.text.font.FontFamily$Resolver { *; }
-keep class androidx.compose.ui.text.font.FontFamilyResolver_androidKt { *; }
-keep class androidx.compose.ui.text.input.ImeAction { *; }
-keep class androidx.compose.ui.text.input.ImeAction$Companion { *; }
-keep class androidx.compose.ui.text.intl.Locale { *; }
-keep class androidx.compose.ui.text.intl.LocaleList { *; }
-keep class androidx.compose.ui.unit.AndroidDensity_androidKt { *; }
-keep class androidx.compose.ui.unit.Constraints { *; }
-keep class androidx.compose.ui.unit.Constraints$Companion { *; }
-keep class androidx.compose.ui.unit.ConstraintsKt { *; }
-keep class androidx.compose.ui.unit.Density { *; }
-keep class androidx.compose.ui.unit.DensityKt { *; }
-keep class androidx.compose.ui.unit.Dp { *; }
-keep class androidx.compose.ui.unit.Dp$Companion { *; }
-keep class androidx.compose.ui.unit.DpRect { *; }
-keep class androidx.compose.ui.unit.DpSize { *; }
-keep class androidx.compose.ui.unit.IntSize { *; }
-keep class androidx.compose.ui.unit.IntSizeKt { *; }
-keep class androidx.compose.ui.unit.LayoutDirection { *; }
-keep class androidx.compose.ui.unit.TextUnit { *; }
-keep class androidx.compose.ui.unit.Velocity { *; }
-keep class androidx.compose.ui.util.ListUtilsKt { *; }
-keep class androidx.compose.ui.util.MathHelpersKt { *; }
-keep class androidx.compose.ui.viewinterop.AndroidView_androidKt { *; }
-keep class androidx.compose.ui.window.DialogWindowProvider { *; }

# ---------------------------------------------------------------------------
# Stage 3: the remaining androidx namespaces (room, activity, core,
# lifecycle, savedstate, collection), narrowed from the whole-namespace
# keeps in proguard-rules.pro. Same derivation as stages 1 and 2 (see the
# header). Notably the extraction found ZERO savedstate references: the
# old broad keep covered the ComponentActivity superclass chain that
# loading transitively needs, but the pair only breaks where the TEST
# references a class R8 removed - and no test code or test library
# references a savedstate class. androidx.room.Room is the one hand
# addition: the test sources import it directly (Room.databaseBuilder),
# and no scanned library constant pool mentions it.
#
# Stage 2 (compose) was validated by release-test run 34868038866, both
# legs green, the x86_64 APK down 20.7 MB to 21,162,616 bytes - the
# whole-namespace compose keep had been freezing the entire Compose
# stack unshrunk.
#
# Follow-up from validation run 34871535994 (both legs, 14 failures,
# two distinct missing symbols). The extraction had scanned the compose
# test libraries at the versions the version catalog pins (BOM
# 2025.04.01 -> ui-test-android 1.8.2), but the resolved graph upgrades
# compose to 1.11.0, whose AndroidInputDispatcher_androidKt clinit
# calls androidx.collection.IntSetKt.intSetOf - a reference no scanned
# constant pool contained, so R8 stripped the method and every
# performClick()/performTouchInput() test died on it. Rescanning the
# 1.11.0 AARs against this file also flagged the call's return type,
# androidx.collection.IntSet. The room miss was different:
# RoomDatabase$Builder appears as a class constant nowhere (not even in
# room-testing 2.7.1) - MigrationInstrumentedTest only reaches it as the
# implicit receiver of Room.databaseBuilder().addMigrations() - so R8
# renamed the Builder and stripped addMigrations() from it.
# ---------------------------------------------------------------------------
-keep class androidx.activity.ComponentActivity { *; }
-keep class androidx.activity.compose.ComponentActivityKt { *; }
-keep class androidx.collection.IntObjectMapKt { *; }
-keep class androidx.collection.IntSet { *; }
-keep class androidx.collection.IntSetKt { *; }
-keep class androidx.collection.MutableIntObjectMap { *; }
-keep class androidx.core.os.ConfigurationCompat { *; }
-keep class androidx.core.os.LocaleListCompat { *; }
-keep class androidx.core.view.ViewConfigurationCompat { *; }
-keep class androidx.core.view.ViewGroupKt { *; }
-keep class androidx.core.view.WindowInsetsCompat { *; }
-keep class androidx.lifecycle.Lifecycle { *; }
-keep class androidx.lifecycle.Lifecycle$Event { *; }
-keep class androidx.lifecycle.Lifecycle$State { *; }
-keep class androidx.lifecycle.LifecycleEventObserver { *; }
-keep class androidx.lifecycle.LifecycleObserver { *; }
-keep class androidx.lifecycle.LifecycleOwner { *; }
-keep class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep class androidx.room.BaseRoomConnectionManager { *; }
-keep class androidx.room.BaseRoomConnectionManager$DriverWrapper { *; }
-keep class androidx.room.DatabaseConfiguration { *; }
-keep class androidx.room.InvalidationTracker { *; }
-keep class androidx.room.Room { *; }
-keep class androidx.room.RoomDatabase { *; }
-keep class androidx.room.RoomDatabase$Builder { *; }
-keep class androidx.room.RoomDatabase$Callback { *; }
-keep class androidx.room.RoomDatabase$JournalMode { *; }
-keep class androidx.room.RoomDatabase$MigrationContainer { *; }
-keep class androidx.room.RoomDatabase$PrepackagedDatabaseCallback { *; }
-keep class androidx.room.RoomOpenDelegate { *; }
-keep class androidx.room.RoomOpenDelegate$ValidationResult { *; }
-keep class androidx.room.Transactor { *; }
-keep class androidx.room.driver.SupportSQLiteConnection { *; }
-keep class androidx.room.driver.SupportSQLiteDriver { *; }
-keep class androidx.room.migration.AutoMigrationSpec { *; }
-keep class androidx.room.migration.Migration { *; }
-keep class androidx.room.migration.bundle.BaseEntityBundle { *; }
-keep class androidx.room.migration.bundle.DatabaseBundle { *; }
-keep class androidx.room.migration.bundle.DatabaseViewBundle { *; }
-keep class androidx.room.migration.bundle.EntityBundle { *; }
-keep class androidx.room.migration.bundle.FieldBundle { *; }
-keep class androidx.room.migration.bundle.ForeignKeyBundle { *; }
-keep class androidx.room.migration.bundle.FtsEntityBundle { *; }
-keep class androidx.room.migration.bundle.IndexBundle { *; }
-keep class androidx.room.migration.bundle.PrimaryKeyBundle { *; }
-keep class androidx.room.migration.bundle.SchemaBundle { *; }
-keep class androidx.room.migration.bundle.SchemaBundle$Companion { *; }
-keep class androidx.room.testing.AndroidMigrationTestHelper { *; }
-keep class androidx.room.testing.BundleUtil { *; }
-keep class androidx.room.testing.ConfigurationFactory { *; }
-keep class androidx.room.testing.ConnectionManagerFactory { *; }
-keep class androidx.room.testing.CreateOpenDelegate { *; }
-keep class androidx.room.testing.DefaultTestConnectionManager { *; }
-keep class androidx.room.testing.MigrateOpenDelegate { *; }
-keep class androidx.room.testing.MigrationTestHelper { *; }
-keep class androidx.room.testing.MigrationTestHelperKt { *; }
-keep class androidx.room.testing.SQLiteDriverMigrationTestHelper { *; }
-keep class androidx.room.testing.SQLiteDriverMigrationTestHelper$createDatabase$connection$1 { *; }
-keep class androidx.room.testing.SQLiteDriverMigrationTestHelper$runMigrationsAndValidate$connection$1 { *; }
-keep class androidx.room.testing.SupportSQLiteMigrationTestHelper { *; }
-keep class androidx.room.testing.SupportSQLiteMigrationTestHelper$SupportTestConnectionManager { *; }
-keep class androidx.room.testing.SupportSQLiteMigrationTestHelper$SupportTestConnectionManager$SupportOpenHelperCallback { *; }
-keep class androidx.room.testing.SupportSQLiteMigrationTestHelper$createDatabase$connection$1 { *; }
-keep class androidx.room.testing.SupportSQLiteMigrationTestHelper$databaseInstance$1 { *; }
-keep class androidx.room.testing.SupportSQLiteMigrationTestHelper$runMigrationsAndValidate$connection$1 { *; }
-keep class androidx.room.testing.TestConnectionManager { *; }
-keep class androidx.room.testing.TestOpenDelegate { *; }
-keep class androidx.room.util.FtsTableInfo { *; }
-keep class androidx.room.util.FtsTableInfo$Companion { *; }
-keep class androidx.room.util.KClassUtil { *; }
-keep class androidx.room.util.TableInfo { *; }
-keep class androidx.room.util.TableInfo$Column { *; }
-keep class androidx.room.util.TableInfo$Companion { *; }
-keep class androidx.room.util.TableInfo$ForeignKey { *; }
-keep class androidx.room.util.TableInfo$Index { *; }
-keep class androidx.room.util.ViewInfo { *; }
-keep class androidx.room.util.ViewInfo$Companion { *; }
