package com.seungjae.jangsu280battery

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.WeakHashMap

/**
 * Reuses the existing lower-right map selector and adds native Kakao map modes without disturbing
 * the proven MapLibre/CyclOSM fallback. This deliberately keeps the map stack reversible:
 * Kakao authentication failure never destroys the existing map.
 */
object KakaoMapSelectorBridge {
    private const val PREFS = "mtb_map_ui"
    private const val KEY_MAP_STYLE = "map_style"
    private const val STYLE_KAKAO_NORMAL = "kakao_normal"
    private const val STYLE_KAKAO_SKYVIEW = "kakao_skyview"
    private const val STYLE_CYCLOSM = "cyclosm"

    private const val MENU_KAKAO_NORMAL = 201
    private const val MENU_KAKAO_SKYVIEW = 202
    private const val MENU_CYCLOSM = 203

    private val installed = WeakHashMap<View, Boolean>()

    fun install(root: View) {
        if (installed[root] == true) return
        val frame = root.findViewById<FrameLayout?>(R.id.layoutRideMapPreview) ?: return
        val selector = findSelector(frame) ?: return
        val smooth = frame.findViewWithTag<View>(RideSmoothMapWebView.TAG_SMOOTH_MAP) as? RideSmoothMapWebView
            ?: return

        installed[root] = true
        selector.setOnClickListener { showMenu(root, frame, selector, smooth) }

        val saved = root.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MAP_STYLE, STYLE_CYCLOSM)
            .orEmpty()
        when (saved) {
            STYLE_KAKAO_NORMAL -> if (KakaoMapSdkGate.hasKey(root.context)) {
                showKakao(frame, smooth, KakaoRideMapView.Mode.NORMAL)
            } else {
                showCyclOsm(frame, smooth)
            }
            STYLE_KAKAO_SKYVIEW -> if (KakaoMapSdkGate.hasKey(root.context)) {
                showKakao(frame, smooth, KakaoRideMapView.Mode.SKYVIEW)
            } else {
                showCyclOsm(frame, smooth)
            }
            else -> showCyclOsm(frame, smooth)
        }
    }

    private fun showMenu(root: View, frame: FrameLayout, anchor: View, smooth: RideSmoothMapWebView) {
        PopupMenu(root.context, anchor).apply {
            menu.add(0, MENU_KAKAO_NORMAL, 0, "카카오 지도")
            menu.add(0, MENU_KAKAO_SKYVIEW, 1, "카카오 스카이뷰 · 도로/지명")
            menu.add(0, MENU_CYCLOSM, 2, "자전거 지도 · CyclOSM")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_KAKAO_NORMAL -> openKakaoOrSetup(root, frame, smooth, KakaoRideMapView.Mode.NORMAL)
                    MENU_KAKAO_SKYVIEW -> openKakaoOrSetup(root, frame, smooth, KakaoRideMapView.Mode.SKYVIEW)
                    else -> {
                        saveStyle(root.context, STYLE_CYCLOSM)
                        showCyclOsm(frame, smooth)
                    }
                }
                true
            }
            show()
        }
    }

    private fun openKakaoOrSetup(
        root: View,
        frame: FrameLayout,
        smooth: RideSmoothMapWebView,
        mode: KakaoRideMapView.Mode
    ) {
        val context = root.context
        if (KakaoMapSdkGate.hasKey(context) && KakaoMapSdkGate.ensureInitialized(context)) {
            saveStyle(context, if (mode == KakaoRideMapView.Mode.SKYVIEW) STYLE_KAKAO_SKYVIEW else STYLE_KAKAO_NORMAL)
            showKakao(frame, smooth, mode)
            return
        }
        showOneTimeSetup(root, frame, smooth, mode)
    }

    private fun showOneTimeSetup(
        root: View,
        frame: FrameLayout,
        smooth: RideSmoothMapWebView,
        mode: KakaoRideMapView.Mode
    ) {
        val context = root.context
        val keyHash = KakaoMapSdkGate.appKeyHash(context)
        val input = EditText(context).apply {
            hint = "Kakao Native App Key"
            isSingleLine = true
        }
        val message = buildString {
            append("카카오 지도는 최초 한 번 Native App Key 등록이 필요합니다.\n\n")
            append("패키지: ").append(context.packageName).append("\n")
            append("키 해시: ").append(keyHash.ifBlank { "확인 실패" }).append("\n\n")
            append("위 패키지와 키 해시를 Kakao Developers의 Android 플랫폼에 등록한 뒤 Native App Key를 아래에 붙여 넣으세요.")
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("카카오 지도 1회 설정")
            .setMessage(message)
            .setView(input)
            .setNegativeButton("취소", null)
            .setNeutralButton("키 해시 복사", null)
            .setPositiveButton("저장하고 열기", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Kakao key hash", keyHash))
                Toast.makeText(context, "키 해시를 복사했습니다.", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val key = input.text?.toString().orEmpty().trim()
                if (key.isBlank()) {
                    input.error = "Native App Key를 입력해 주세요."
                    return@setOnClickListener
                }
                KakaoMapSdkGate.saveLocalKey(context, key)
                if (!KakaoMapSdkGate.ensureInitialized(context)) {
                    Toast.makeText(context, "카카오 SDK 초기화에 실패했습니다. 키를 확인해 주세요.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                saveStyle(context, if (mode == KakaoRideMapView.Mode.SKYVIEW) STYLE_KAKAO_SKYVIEW else STYLE_KAKAO_NORMAL)
                dialog.dismiss()
                showKakao(frame, smooth, mode)
            }
        }
        dialog.show()
    }

    private fun showKakao(frame: FrameLayout, smooth: RideSmoothMapWebView, mode: KakaoRideMapView.Mode) {
        if (!KakaoMapSdkGate.ensureInitialized(frame.context)) return
        var kakao = frame.findViewWithTag<View>(KakaoRideMapView.TAG_KAKAO_MAP) as? KakaoRideMapView
        if (kakao == null) {
            kakao = KakaoRideMapView(frame.context)
            val smoothIndex = frame.indexOfChild(smooth).takeIf { it >= 0 } ?: 0
            frame.addView(
                kakao,
                (smoothIndex + 1).coerceAtMost(frame.childCount),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        smooth.visibility = View.GONE
        kakao.setMode(mode)
        kakao.setActive(true)
        attribution(frame)?.visibility = View.GONE
    }

    private fun showCyclOsm(frame: FrameLayout, smooth: RideSmoothMapWebView) {
        (frame.findViewWithTag<View>(KakaoRideMapView.TAG_KAKAO_MAP) as? KakaoRideMapView)?.setActive(false)
        smooth.visibility = View.VISIBLE
        val js = """
            (function(){
              try {
                if (typeof map !== 'undefined' && map) {
                  var src=map.getSource('osm');
                  if(src && typeof src.setTiles==='function') src.setTiles(['https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png']);
                }
              } catch(e) {}
            })();
        """.trimIndent()
        runCatching { smooth.evaluateJavascript(js, null) }
        attribution(frame)?.apply {
            text = "© OpenStreetMap · CyclOSM"
            visibility = View.VISIBLE
        }
    }

    private fun findSelector(frame: FrameLayout): TextView? {
        for (i in 0 until frame.childCount) {
            val v = frame.getChildAt(i)
            if (v is TextView && v.contentDescription?.toString() == "지도 종류 선택") return v
        }
        return null
    }

    private fun attribution(frame: FrameLayout): TextView? {
        for (i in 0 until frame.childCount) {
            val v = frame.getChildAt(i)
            if (v is TextView && v.text?.toString()?.startsWith("© OpenStreetMap") == true) return v
        }
        return null
    }

    private fun saveStyle(context: Context, style: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MAP_STYLE, style)
            .apply()
    }
}
