package io.nekohasekai.sagernet;

import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;

/** Java bypasses Kotlin metadata's newer runBlockingK mapping for the cross-version harness. */
public final class LegacyCoroutineBridge {
    private LegacyCoroutineBridge() {}

    /** The caller supplies a suspend CoroutineScope receiver lambda (its JVM ABI is Function2). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <T> T runBlocking(Object block) throws InterruptedException {
        return (T) BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (Function2) block);
    }
}
