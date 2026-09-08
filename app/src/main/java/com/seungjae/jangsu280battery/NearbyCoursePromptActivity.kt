package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

/** Small user-facing confirmation launched from the 10 km nearby-course notification. */
class NearbyCoursePromptActivity : Activity() {
    private lateinit var repo: CourseRepository
    private lateinit var registry: PublicCourseRegistry
    private lateinit var client: PublicCourseClient
    private lateinit var status: TextView
    private lateinit var download: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = CourseRepository(this); registry = PublicCourseRegistry(this); client = PublicCourseClient(this)
        buildUi()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(28))
        }
        scroll.addView(body); setContentView(scroll)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(Button(this).apply { text = "‹"; textSize = 28f; setBackgroundColor(Color.TRANSPARENT); setOnClickListener { finish() } }, LinearLayout.LayoutParams(dp(58), dp(58)))
        bar.addView(TextView(this).apply { text = "근처 공개 코스"; textSize = 24f; setTextColor(Color.BLACK); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, dp(58), 1f))
        body.addView(bar)

        val items = registry.cachedNearby()
        body.addView(TextView(this).apply {
            text = if (items.isEmpty()) "현재 다운로드할 근처 코스가 없습니다." else "START 지점 반경 10km 안에 공개된 코스 ${items.size}개가 있습니다.\n모두 휴대폰 TimeGate 코스 폴더에 저장할까요?"
            textSize = 16f; setTextColor(Color.rgb(20, 25, 32)); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(10), 0, dp(12))
        })

        items.forEach { c ->
            body.addView(TextView(this).apply {
                val event = c.eventNames.firstOrNull()?.let { " · $it" }.orEmpty()
                text = "• ${c.name} · ${"%.2f".format(Locale.US, c.distanceKm)}km · START에서 ${formatDistance(c.distanceFromUserM)}$event"
                textSize = 13f; setTextColor(Color.rgb(70, 80, 94)); setPadding(dp(4), dp(5), dp(4), dp(5))
            })
        }

        status = TextView(this).apply { textSize = 13f; setTextColor(Color.rgb(80, 90, 105)); setPadding(0, dp(14), 0, dp(8)) }
        body.addView(status)

        download = Button(this).apply {
            text = "모두 다운로드"
            isAllCaps = false
            textSize = 17f
            isEnabled = items.isNotEmpty()
            setOnClickListener { downloadAll(items) }
        }
        body.addView(download, LinearLayout.LayoutParams(-1, dp(54)))
        body.addView(Button(this).apply { text = "나중에"; isAllCaps = false; setOnClickListener { finish() } }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
    }

    private fun downloadAll(items: List<PublicCourseClient.NearbyCourse>) {
        if (items.isEmpty()) return
        download.isEnabled = false; status.text = "공개 GPX 확인 중…"
        Thread {
            var downloaded = 0; var reused = 0; var failed = 0; var lastLocalId: String? = null
            items.forEachIndexed { index, c ->
                runOnUiThread { status.text = "${index + 1}/${items.size} · ${c.name}" }
                val existing = registry.resolveExisting(c, repo)
                if (existing != null) {
                    reused++; lastLocalId = existing
                } else {
                    val ok = runCatching {
                        val tmp = client.download(c)
                        try {
                            val meta = repo.importGpxFile(tmp, c.name, enqueueServer = false)
                            registry.remember(c.serverCourseId, c.sha256, meta.id)
                            lastLocalId = meta.id; downloaded++
                        } finally { tmp.delete() }
                    }.isSuccess
                    if (!ok) failed++
                }
            }
            lastLocalId?.let { runCatching { repo.setActive(it) } }
            runOnUiThread {
                status.text = "완료 · 새 다운로드 ${downloaded}개 · 기존 ${reused}개${if (failed > 0) " · 실패 ${failed}개" else ""}"
                Toast.makeText(this, "근처 코스를 TimeGate에 저장했습니다.", Toast.LENGTH_LONG).show()
                download.isEnabled = failed > 0
            }
        }.start()
    }

    private fun formatDistance(m: Double): String = if (m < 1000.0) "${m.roundToInt()}m" else "${"%.1f".format(Locale.US, m / 1000.0)}km"
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}