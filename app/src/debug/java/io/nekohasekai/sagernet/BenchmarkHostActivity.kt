package io.nekohasekai.sagernet

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Debug-only foreground owner. No profile, database, service or UI observers. */
class BenchmarkHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Vialen benchmark running\nPlease leave this test screen open."
            gravity = Gravity.CENTER
        })
    }
}
