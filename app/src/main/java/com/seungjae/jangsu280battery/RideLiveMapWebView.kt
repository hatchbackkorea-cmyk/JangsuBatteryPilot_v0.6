package com.seungjae.jangsu280battery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import org.json.JSONArray
import java.util.Locale

/**
 * MTB HUD live map.
 *
 * The existing Kakao static-map ImageView stays underneath this view as a fallback.
 * This WebView draws the selected GPX route on an OpenStreetMap/Leaflet map and follows
 * the same RideService GPS broadcast already used by MainActivity, so no second GPS
 * subscription is created.
 */
@SuppressLint("SetJavaScriptEnabled")
class RideLiveMapWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    private val courseRepo = CourseRepository(context)
    private var pageReady = false
    private var receiverRegistered = false
    private var activeCourseId: String? = null
    private var latestLat = Double.NaN
    private var latestLon = Double.NaN
    private var latestKm = 0.0

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE) return
            if (intent.hasExtra(RideService.EXTRA_LAT)) {
                latestLat = intent.getDoubleExtra(RideService.EXTRA_LAT, latestLat)
            }
            if (intent.hasExtra(RideService.EXTRA_LON)) {
                latestLon = intent.getDoubleExtra(RideService.EXTRA_LON, latestLon)
            }
            latestKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestKm)
            if (latestLat.isFinite() && latestLon.isFinite()) {
                pushLocation(latestLat, latestLon, latestKm)
            }
        }
    }

    init {
        tag = TAG_LIVE_MAP
        alpha = 0f
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                refreshCourse(force = true)
                if (latestLat.isFinite() && latestLon.isFinite()) {
                    pushLocation(latestLat, latestLon, latestKm)
                }
                animate().alpha(1f).setDuration(180L).start()
                updateStatus("실시간 지도 · GPX + 현재 위치 · 지도에서 확대/이동 가능")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageReady = false
                    alpha = 0f
                    updateStatus("실시간 지도 연결 실패 · 카카오맵 미리보기 사용")
                }
            }
        }

        loadDataWithBaseURL(
            "https://ride-copilot.local/",
            HTML,
            "text/html",
            "UTF-8",
            null
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerRideReceiver()
        post { refreshCourse(force = false) }
    }

    override fun onDetachedFromWindow() {
        unregisterRideReceiver()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) post { refreshCourse(force = false) }
    }

    private fun registerRideReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(RideService.ACTION_UPDATE)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(rideReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(rideReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            receiverRegistered = false
        }
    }

    private fun unregisterRideReceiver() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(rideReceiver) } catch (_: Exception) {}
        receiverRegistered = false
    }

    private fun refreshCourse(force: Boolean) {
        if (!pageReady) return
        val meta = runCatching { courseRepo.activeMeta() }.getOrNull() ?: return
        if (!force && activeCourseId == meta.id) return
        val course = runCatching { courseRepo.loadCourse(meta.id) }.getOrNull() ?: return
        activeCourseId = meta.id

        val points = downsample(course.track, MAX_ROUTE_POINTS)
        val routeJson = JSONArray()
        points.forEach { p ->
            routeJson.put(JSONArray().apply {
                put(p.lat)
                put(p.lon)
                put(p.routeKm)
            })
        }
        val nameJson = org.json.JSONObject.quote(meta.name)
        evaluateJavascript(
            "window.rcSetRoute && window.rcSetRoute(${routeJson},$nameJson);",
            null
        )
        updateStatus("실시간 지도 · ${meta.name} · GPS 대기")
    }

    private fun pushLocation(lat: Double, lon: Double, km: Double) {
        if (!pageReady || !lat.isFinite() || !lon.isFinite()) return
        val safeKm = if (km.isFinite()) km.coerceAtLeast(0.0) else 0.0
        val js = String.format(
            Locale.US,
            "window.rcSetLocation && window.rcSetLocation(%.7f,%.7f,%.3f);",
            lat,
            lon,
            safeKm
        )
        evaluateJavascript(js, null)
        updateStatus("실시간 지도 · 현재 ${String.format(Locale.KOREA, "%.1f", safeKm)} km · GPS 추적 중")
    }

    private fun updateStatus(text: String) {
        post {
            val status = rootView.findViewById<TextView?>(R.id.tvRideMapPreviewStatus)
            status?.text = text
        }
    }

    private fun downsample(input: List<TrackPoint>, maxPoints: Int): List<TrackPoint> {
        if (input.size <= maxPoints) return input
        val out = ArrayList<TrackPoint>(maxPoints + 1)
        val step = (input.size - 1).toDouble() / (maxPoints - 1).toDouble()
        for (i in 0 until maxPoints) {
            out += input[(i * step).toInt().coerceIn(input.indices)]
        }
        if (out.lastOrNull() !== input.lastOrNull()) out += input.last()
        return out
    }

    companion object {
        const val TAG_LIVE_MAP = "ride_live_map_v0329"
        private const val MAX_ROUTE_POINTS = 800

        private val HTML = """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
              <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
              <style>
                html,body,#map{width:100%;height:100%;margin:0;padding:0;background:#111820;overflow:hidden}
                .leaflet-control-attribution{font-size:8px!important}
              </style>
            </head>
            <body>
              <div id="map"></div>
              <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
              <script>
                const map=L.map('map',{zoomControl:true,attributionControl:true,preferCanvas:true});
                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{
                  maxZoom:19,
                  attribution:'&copy; OpenStreetMap'
                }).addTo(map);
                let routePts=[];
                let routeLine=null;
                let doneLine=null;
                let rider=null;
                let firstLocation=true;

                window.rcSetRoute=function(points,name){
                  routePts=Array.isArray(points)?points:[];
                  if(routeLine) map.removeLayer(routeLine);
                  if(doneLine) map.removeLayer(doneLine);
                  const latLngs=routePts.map(p=>[p[0],p[1]]);
                  routeLine=L.polyline(latLngs,{color:'#42c6ff',weight:5,opacity:0.92,lineCap:'round'}).addTo(map);
                  doneLine=L.polyline([],{color:'#38d67a',weight:6,opacity:0.98,lineCap:'round'}).addTo(map);
                  if(latLngs.length>1){
                    map.fitBounds(routeLine.getBounds(),{padding:[18,18]});
                  }else if(latLngs.length===1){
                    map.setView(latLngs[0],16);
                  }
                };

                window.rcSetLocation=function(lat,lon,km){
                  const pos=[lat,lon];
                  if(!rider){
                    rider=L.circleMarker(pos,{radius:9,color:'#ffffff',weight:3,fillColor:'#ff4b2b',fillOpacity:1}).addTo(map);
                  }else{
                    rider.setLatLng(pos);
                  }
                  if(doneLine && routePts.length){
                    const done=[];
                    for(let i=0;i<routePts.length;i++){
                      const p=routePts[i];
                      if(p[2] <= km + 0.03) done.push([p[0],p[1]]); else break;
                    }
                    if(done.length) done.push(pos);
                    doneLine.setLatLngs(done);
                  }
                  if(firstLocation){
                    map.setView(pos,16,{animate:false});
                    firstLocation=false;
                  }else{
                    map.panTo(pos,{animate:true,duration:0.35,noMoveStart:true});
                  }
                };
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
