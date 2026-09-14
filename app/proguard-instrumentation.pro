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
