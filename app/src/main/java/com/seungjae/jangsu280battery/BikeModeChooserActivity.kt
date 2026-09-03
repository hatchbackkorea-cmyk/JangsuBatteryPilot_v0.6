package com.seungjae.jangsu280battery

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class BikeModeChooserActivity : Activity() {
    private lateinit var sync: RiderServerSync
    private lateinit var btnAdmin: Button
    private lateinit var tvVersion: TextView
    private lateinit var tvRoomStatus: TextView
    private lateinit var roomContainer: LinearLayout
    private lateinit var roomDiscovery: GroupRoomDiscovery
    @Volatile private var roomRefreshBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bike_mode_chooser)
        sync = RiderServerSync(this)
        roomDiscovery = GroupRoomDiscovery(this)
        btnAdmin = findViewById(R.id.btnBikeModeAdmin)
        tvVersion = findViewById(R.id.tvBikeModeVersion)
        tvRoomStatus = findViewById(R.id.tvBikeModeRoomStatus)
        roomContainer = findViewById(R.id.llBikeModePublicRooms)

        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""
        tvVersion.text = "Ride Copilot v$version"
        findViewById<Button>(R.id.btnBikeModeEmtb).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.btnBikeModeRoad).setOnClickListener {
            startActivity(Intent(this, RoadGranfondoActivity::class.java))
        }
        findViewById<Button>(R.id.btnBikeModeRoomRefresh).setOnClickListener { refreshPublicRooms() }
        btnAdmin.setOnClickListener {
            if (sync.isAdminDeviceCached()) startActivity(Intent(this, AdminCenterActivity::class.java))
            else refreshAdminVisibility()
        }

        // Hidden bootstrap: non-admin phones never show an admin menu.
        tvVersion.setOnLongClickListener {
            showAdminPhonePairDialog()
            true
        }
        refreshAdminVisibility()
        refreshPublicRooms()
    }

    override fun onResume() {
        super.onResume()
        refreshAdminVisibility()
        if (sync.configured()) {
            sync.checkAdminStatusAsync {
                runOnUiThread { refreshAdminVisibility() }
            }
        }
        if (sync.autoEnabled() && sync.configured()) sync.syncAllAsync()
        refreshPublicRooms()
    }

    private fun refreshPublicRooms() {
        if (roomRefreshBusy) return
        roomRefreshBusy = true
        tvRoomStatus.text = "내 PC Rider Control Center와 공개방을 찾는 중…"
        Thread {
            val result = runCatching {
                val server = roomDiscovery.resolveServer() ?: error("Rider Control Center 서버를 찾지 못했습니다.")
                server to roomDiscovery.fetchRooms(server)
            }
            runOnUiThread {
                roomRefreshBusy = false
                result.onSuccess { (server, rooms) -> renderRooms(server, rooms) }
                    .onFailure {
                        roomContainer.removeAllViews()
                        tvRoomStatus.text = "공개방을 찾지 못했습니다 · PC 서버가 켜져 있는지 확인하세요."
                    }
            }
        }.start()
    }

    private fun renderRooms(server: String, rooms: List<PublicGroupRoom>) {
        roomContainer.removeAllViews()
        if (rooms.isEmpty()) {
            tvRoomStatus.text = "서버 연결됨 · 현재 공개 그룹방이 없습니다."
            return
        }
        tvRoomStatus.text = "공개방 ${rooms.size}개 · 원하는 방을 누르면 이름만 입력하고 바로 참가합니다."
        rooms.forEach { room ->
            val course = if (room.hasCourse) {
                val km = if (room.courseDistanceKm > 0.0) " · ${String.format("%.1f", room.courseDistanceKm)}km" else ""
                "\nGPX ${room.courseName.ifBlank { "코스 설정됨" }}$km"
            } else "\nGPX 미설정"
            val b = Button(this).apply {
                isAllCaps = false
                text = "🚴 ${room.title}  [${room.room}]\n참가 ${room.connectedCount}/${room.maxRiders}명$course"
                textSize = 14f
                minHeight = 76
                setOnClickListener { showGuestJoinDialog(server, room) }
            }
            roomContainer.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 6
            })
        }
    }

    private fun showGuestJoinDialog(server: String, room: PublicGroupRoom) {
        val name = EditText(this).apply {
            hint = "참가자 이름 또는 닉네임"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            setText(roomDiscovery.lastNickname())
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${room.title} 참가")
            .setMessage("별도 연결 토큰 없이 이 방에만 사용할 임시 참가권을 자동 발급합니다.${if (room.hasCourse) "\n입장하면 방 GPX도 자동으로 내려받습니다." else ""}")
            .setView(name)
            .setPositiveButton("바로 참가", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val nick = name.text.toString().trim()
                if (nick.isBlank()) {
                    name.error = "이름을 입력하세요."
                    return@setOnClickListener
                }
                ok.isEnabled = false
                Thread {
                    val result = runCatching { roomDiscovery.joinRoom(server, room.room, nick) }
                    runOnUiThread {
                        ok.isEnabled = true
                        result.onSuccess { session ->
                            dialog.dismiss()
                            startActivity(Intent(this, RoadGranfondoActivity::class.java).apply {
                                putExtra(GroupRoomDiscovery.EXTRA_GUEST_SERVER, session.serverUrl)
                                putExtra(GroupRoomDiscovery.EXTRA_GUEST_ROOM, session.room)
                                putExtra(GroupRoomDiscovery.EXTRA_GUEST_NICK, session.nickname)
                                putExtra(GroupRoomDiscovery.EXTRA_GUEST_TOKEN, session.token)
                            })
                        }.onFailure {
                            Toast.makeText(this, "그룹방 참가 실패: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun refreshAdminVisibility() {
        btnAdmin.visibility = if (sync.isAdminDeviceCached()) View.VISIBLE else View.GONE
    }

    private fun showAdminPhonePairDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 8, 36, 0)
        }
        val server = EditText(this).apply {
            hint = "Rider Control Center HTTPS 주소"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(sync.serverUrl())
        }
        val code = EditText(this).apply {
            hint = "PC에서 발급한 8자리 관리자폰 등록 코드"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(server)
        box.addView(code)
        val dialog = AlertDialog.Builder(this)
            .setTitle("관리자폰 등록")
            .setMessage("이 화면은 일반 사용자에게 표시되지 않는 숨은 등록 화면입니다. PC 관리자에서 발급한 8자리 코드는 5분 동안만 유효합니다.")
            .setView(box)
            .setPositiveButton("등록", null)
            .setNegativeButton("취소", null)
            .create()
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                ok.isEnabled = false
                sync.pairAdminPhoneAsync(server.text.toString(), code.text.toString()) { result ->
                    runOnUiThread {
                        ok.isEnabled = true
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                        if (result.ok) {
                            refreshAdminVisibility()
                            dialog.dismiss()
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
