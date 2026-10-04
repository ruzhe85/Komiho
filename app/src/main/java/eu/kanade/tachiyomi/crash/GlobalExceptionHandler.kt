package eu.kanade.tachiyomi.crash

import android.content.Context
import android.content.Intent
import android.os.Looper
import eu.kanade.tachiyomi.diagnostic.DiagLog
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class GlobalExceptionHandler private constructor(
    private val applicationContext: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler,
    private val activityToBeLaunched: Class<*>,
) : Thread.UncaughtExceptionHandler {

    object ThrowableSerializer : KSerializer<Throwable> {
        override val descriptor: SerialDescriptor =
            PrimitiveSerialDescriptor("Throwable", PrimitiveKind.STRING)

        override fun deserialize(decoder: Decoder): Throwable =
            Throwable(message = decoder.decodeString())

        override fun serialize(encoder: Encoder, value: Throwable) =
            encoder.encodeString(value.stackTraceToString())
    }

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        // Komiho (2026-10-04): 显式把异常写进诊断日志（DiagLog 双写系统 logcat + 落盘缓冲）。
        // 带线程名与「是否主线程」，并**把栈拼进 message** —— 落盘缓冲只记 message，
        // throwable 参数只进系统 logcat（导不出来的设备就看不到）。栈换行会被压成空格，
        // 因此仍是一行、可整行检索。
        DiagLog.e(
            "Crash",
            "FATAL 未捕获异常 thread=${thread.name}(main=${thread === Looper.getMainLooper().thread}) " +
                "${exception.javaClass.name}: ${exception.message}\n${exception.stackTraceToString()}",
            exception,
        )
        logcat(priority = LogPriority.ERROR, throwable = exception)
        launchActivity(applicationContext, activityToBeLaunched, exception)
        defaultHandler.uncaughtException(thread, exception)
    }

    private fun launchActivity(
        applicationContext: Context,
        activity: Class<*>,
        exception: Throwable,
    ) {
        val intent = Intent(applicationContext, activity).apply {
            putExtra(INTENT_EXTRA, Json.encodeToString(ThrowableSerializer, exception))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        applicationContext.startActivity(intent)
    }

    companion object {
        private const val INTENT_EXTRA = "Throwable"

        fun initialize(
            applicationContext: Context,
            activityToBeLaunched: Class<*>,
        ) {
            val handler = GlobalExceptionHandler(
                applicationContext,
                Thread.getDefaultUncaughtExceptionHandler() as Thread.UncaughtExceptionHandler,
                activityToBeLaunched,
            )
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }

        fun getThrowableFromIntent(intent: Intent): Throwable? {
            return try {
                Json.decodeFromString(ThrowableSerializer, intent.getStringExtra(INTENT_EXTRA)!!)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Wasn't able to retrieve throwable from intent" }
                null
            }
        }
    }
}
