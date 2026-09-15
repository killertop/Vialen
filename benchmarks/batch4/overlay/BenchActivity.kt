package io.nekohasekai.sagernet.benchmark

import android.app.*
import android.os.*
import android.content.Intent
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.core.Profile
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.ui.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Included only by the external measurement overlay, never the production source set. */
object BenchCounters {
    val queries = AtomicLong(); val profileReads = AtomicLong(); val writes = AtomicLong()
    @Volatile var enabled = false
    fun sql(sql: String) {
        if (!enabled) return
        queries.incrementAndGet()
        if (sql.startsWith("SELECT", true) && sql.contains("proxy_entities", true)) profileReads.incrementAndGet()
        if (sql.startsWith("UPDATE", true) || sql.startsWith("INSERT", true) || sql.startsWith("DELETE", true)) writes.incrementAndGet()
    }
    fun reset() { queries.set(0); profileReads.set(0); writes.set(0) }
    fun json() = JSONObject().put("queries",queries.get()).put("profile_reads",profileReads.get()).put("writes",writes.get())
}

class BenchActivity : Activity(), Application.ActivityLifecycleCallbacks {
    private val executor = Executors.newSingleThreadExecutor { Thread(it,"B4Driver") }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    private val resumed = CompletableDeferred<MainActivity>()
    private var measured: MainActivity? = null
    private val framesThread = HandlerThread("B4Frames")
    private val frames = ArrayList<LongArray>()
    @Volatile private var recording = false
    private lateinit var output: File
    private val result = JSONObject()
    private val metrics = Window.OnFrameMetricsAvailableListener { _, m, dropped ->
        if (recording) synchronized(frames) { frames.add(longArrayOf(m.getMetric(FrameMetrics.TOTAL_DURATION),
            m.getMetric(FrameMetrics.DEADLINE),m.getMetric(FrameMetrics.GPU_DURATION),dropped.toLong())) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.vialen.app.benchmark")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(android.widget.TextView(this).apply { text="Vialen controlled measurement" })
        val run = intent.getStringExtra("run") ?: "preflight"
        require(run.matches(Regex("[a-zA-Z0-9_-]{1,100}")))
        output=File(getExternalFilesDir(null),"b4/$run.json").also { it.parentFile!!.mkdirs() }
        val mode=intent.getStringExtra("mode") ?: "list"
        val n=intent.getIntExtra("count",100); require(n in 0..10000)
        result.put("run",run).put("mode",mode).put("nodes",n).put("seed",413)
        result.put("debuggable",applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        application.registerActivityLifecycleCallbacks(this)
        framesThread.start()
        scope.launch {
            try {
                withTimeout(120000) {
                    val group = fixtureGroup()
                    DataStore.configurationStore.putBoolean("isAutoConnect",false)
                    DataStore.selectedGroup=group.id
                    if (mode=="seed") {
                        SagerDatabase.instance.runInTransaction {
                            SagerDatabase.proxyDao.deleteByGroup(group.id)
                            nodes(n).forEachIndexed { i, p -> SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId=group.id,userOrder=i+1L).putProfile(p)) }
                        }
                        DataStore.selectedProxy=SagerDatabase.proxyDao.getIdsByGroup(group.id).firstOrNull() ?: 0
                        result.put("inserted",SagerDatabase.proxyDao.getIdsByGroup(group.id).size)
                    } else {
                        val begin=SystemClock.elapsedRealtimeNanos()
                        withContext(Dispatchers.Main) { startActivity(Intent(this@BenchActivity,MainActivity::class.java)) }
                        val activity=resumed.await()
                        val fragment=awaitList(activity, if(mode=="import") 0 else n)
                        result.put("load_ms",(SystemClock.elapsedRealtimeNanos()-begin)/1e6)
                        result.put("load_frames",finishFrames())
                        delay(500) // Fixed pre-window settling; not part of the measurement.
                        when(mode) {
                            "list" -> {
                                // Identical unreported scroll warm-up in each run.
                                scroll(fragment,1000,500); scroll(fragment,-1000,500)
                                window("scroll") { repeat(4) { scroll(fragment,if(it%2==0) 2200 else -2200,1000) } }
                                val ids=SagerDatabase.proxyDao.getIdsByGroup(group.id).take(100)
                                SagerDatabase.instance.runInTransaction { ids.forEach { SagerDatabase.proxyDao.updateConnectionTestResult(it,1,731,null) } }
                                window("refresh") {
                                    withContext(Dispatchers.Main) { fragment.adapter!!.reloadProfiles() }
                                    awaitCondition { fragment.adapter!!.configurationList[ids.first()]?.ping==731 }
                                    drawn(fragment.configurationListView)
                                }
                                window("burst") {
                                    withContext(Dispatchers.Main) { repeat(100) { fragment.adapter!!.reloadProfiles() } }
                                    // A final row change proves the final queued read has published.
                                    SagerDatabase.proxyDao.updateConnectionTestResult(ids.first(),1,733,null)
                                    withContext(Dispatchers.Main) { fragment.adapter!!.reloadProfiles() }
                                    awaitCondition { fragment.adapter!!.configurationList[ids.first()]?.ping==733 }
                                    drawn(fragment.configurationListView)
                                }
                            }
                            "import" -> window("import") {
                                val imported=ProfileManager.createProfilesForImport(group.id,nodes(n))
                                result.put("committed_rows",imported.size)
                                check(SagerDatabase.proxyDao.getIdsByGroup(group.id).size==n)
                                awaitCondition { fragment.adapter!!.itemCount==n }
                                drawn(fragment.configurationListView)
                                result.put("selection_valid",DataStore.selectedProxy in imported.map { it.id })
                            }
                            "jni" -> BenchNative.run(n,result)
                            "gc" -> BenchNative.gc(result)
                            else -> error("unsupported mode")
                        }
                    }
                    result.put("ok",true)
                }
            } catch(error:Throwable) {
                // Only type, never arbitrary exception text or runtime configuration.
                result.put("ok",false).put("error_type",error.javaClass.simpleName)
            } finally {
                BenchCounters.enabled=false
                result.put("fixture_hash",fixtureHash(n))
                output.writeText(result.toString())
                withContext(NonCancellable+Dispatchers.Main) {
                    measured?.window?.removeOnFrameMetricsAvailableListener(metrics)
                    application.unregisterActivityLifecycleCallbacks(this@BenchActivity)
                    measured?.finish(); finish()
                }
                framesThread.quitSafely(); executor.shutdown()
            }
        }
    }
    private suspend fun window(name:String, block:suspend ()->Unit) {
        BenchCounters.reset();BenchCounters.enabled=true
        synchronized(frames){frames.clear()};recording=true
        val memBefore=memory()
        val start=SystemClock.elapsedRealtimeNanos();val cpu=Process.getElapsedCpuTime();val driver=Debug.threadCpuTimeNanos()
        android.os.Trace.beginAsyncSection("B4_$name",1)
        try { block() } finally {
            android.os.Trace.endAsyncSection("B4_$name",1)
            val elapsed=SystemClock.elapsedRealtimeNanos()-start
            val entry=JSONObject().put("elapsed_ms",elapsed/1e6).put("process_cpu_ms",Process.getElapsedCpuTime()-cpu)
                .put("driver_cpu_ms",(Debug.threadCpuTimeNanos()-driver)/1e6).put("frames",finishFrames())
                .put("sql",BenchCounters.json()).put("memory_before",memBefore).put("memory_after",memory())
            result.put(name,entry);BenchCounters.enabled=false
        }
    }
    private fun memory():JSONObject {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        return JSONObject().put("pss_kb",info.totalPss).put("java_used_bytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
    }
    private fun finishFrames():JSONArray {
        recording=false
        return synchronized(frames) { JSONArray(frames.map { JSONArray(it.toList()) }).also { frames.clear() } }
    }
    private suspend fun scroll(f:ConfigurationFragment.GroupFragment,dy:Int,duration:Int) {
        val done=CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val view=f.configurationListView
            view.addOnScrollListener(object:RecyclerView.OnScrollListener(){
                override fun onScrollStateChanged(v:RecyclerView,state:Int) {
                    if(state==RecyclerView.SCROLL_STATE_IDLE){v.removeOnScrollListener(this);done.complete(Unit)}
                }
            })
            view.smoothScrollBy(0,dy,android.view.animation.LinearInterpolator(),duration)
        }
        withTimeout(duration+3000L){done.await()};drawn(f.configurationListView)
    }
    private suspend fun drawn(view:View) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<Unit> { c ->
            view.postOnAnimation { view.postOnAnimation { if(c.isActive)c.resume(Unit) {} } }
            view.invalidate()
        }
    }
    private suspend fun awaitCondition(test:()->Boolean) {
        withTimeout(30000) { while(!withContext(Dispatchers.Main){test()}) delay(16) }
    }
    private suspend fun awaitList(a:MainActivity,n:Int):ConfigurationFragment.GroupFragment {
        var found:ConfigurationFragment.GroupFragment?=null
        fun all(f:Fragment):Sequence<Fragment> = sequence { yield(f);f.childFragmentManager.fragments.forEach { yieldAll(all(it)) } }
        awaitCondition {
            val owner=a.supportFragmentManager.fragments.asSequence().flatMap { all(it) }.filterIsInstance<ConfigurationFragment>().firstOrNull()
            found=owner?.getCurrentGroupFragment()
            val f=found
            f!=null && f.adapter?.itemCount==n && (n==0 || f.configurationListView.childCount>0) && !f.configurationListView.isComputingLayout
        }
        drawn(found!!.configurationListView);return found!!
    }
    private fun fixtureGroup():ProxyGroup {
        val all=SagerDatabase.groupDao.allGroups()
        check(all.all { it.ungrouped || it.name=="batch4-fixture" }) { "Not a fresh benchmark fixture" }
        return all.firstOrNull { it.name=="batch4-fixture" } ?: ProxyGroup(name="batch4-fixture").apply { id=SagerDatabase.groupDao.createGroup(this) }
    }
    private fun nodes(n:Int)= (0 until n).map { i-> Profile(name="Fixture-${i.toString().padStart(5,'0')}",type="socks",server="n${i+413}.example.test",port=1080,socks=Profile.Socks()) }
    private fun fixtureHash(n:Int):String {
        val bytes=(0 until n).joinToString("\n"){"$it|n${it+413}.example.test|1080|socks"}.toByteArray()
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
    override fun onActivityCreated(a:Activity,b:Bundle?) { if(a is MainActivity){measured=a;recording=true;a.window.addOnFrameMetricsAvailableListener(metrics,Handler(framesThread.looper))} }
    override fun onActivityResumed(a:Activity){if(a is MainActivity){measured=a;resumed.complete(a)}}
    override fun onActivityStarted(a:Activity){};override fun onActivityPaused(a:Activity){}
    override fun onActivityStopped(a:Activity){};override fun onActivitySaveInstanceState(a:Activity,b:Bundle){}
    override fun onActivityDestroyed(a:Activity){}
}
