package com.seungjae.jangsu280battery

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import org.json.JSONArray
import java.util.Locale

/**
 * MTB HUD live navigation map.
 *
 * v0.33.1:
 * - map display uses the phone GPS directly at 5 Hz by default (1 Hz selectable)
 * - current position is available even before RideService starts a ride
 * - the map stays in a neutral waiting screen until the first real phone GPS fix
 * - rider marker is a small heading-up triangle instead of a dot
 * - RideService broadcasts are still consumed for route-progress context, but no longer
 *   limit visible map refresh rate.
 */
@SuppressLint("SetJavaScriptEnabled")
class RideLiveMapWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs), LocationListener {

    private val courseRepo = CourseRepository(context)
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val gpsPrefs = context.getSharedPreferences("ride_live_map_gps", Context.MODE_PRIVATE)

    private var pageReady = false
    private var mapVerified = false
    private var receiverRegistered = false
    private var directGpsStarted = false
    private var activeCourseId: String? = null
    private var activeCourse: CourseData? = null

    private var latestLat = Double.NaN
    private var latestLon = Double.NaN
    private var latestKm = 0.0
    private var latestSpeedKmh = 0.0
    private var gpsRateHz = if (gpsPrefs.getInt(KEY_GPS_RATE_HZ, 5) == 1) 1 else 5
    private var lastFixElapsedMs = 0L
    private var actualHz = 0.0

    private val gpsBridge = object {
        @JavascriptInterface
        fun toggleGpsRate() {
            post {
                gpsRateHz = if (gpsRateHz == 5) 1 else 5
                gpsPrefs.edit().putInt(KEY_GPS_RATE_HZ, gpsRateHz).apply()
                restartDirectGps()
                pushGpsRateToPage()
                updateStatusText()
            }
        }
    }

    private val rideReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RideService.ACTION_UPDATE) return
            latestKm = intent.getDoubleExtra(RideService.EXTRA_ROUTE_KM, latestKm)
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)

            // If direct GPS could not start, keep the previous RideService path as fallback.
            if (!directGpsStarted && intent.hasExtra(RideService.EXTRA_LAT) && intent.hasExtra(RideService.EXTRA_LON)) {
                latestLat = intent.getDoubleExtra(RideService.EXTRA_LAT, latestLat)
                latestLon = intent.getDoubleExtra(RideService.EXTRA_LON, latestLon)
                if (latestLat.isFinite() && latestLon.isFinite()) {
                    pushLocation(latestLat, latestLon, latestKm, latestSpeedKmh)
                }
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
        addJavascriptInterface(gpsBridge, "RiderGps")

        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageReady = true
                mapVerified = false
                refreshCourse(force = true)
                pushGpsRateToPage()
                if (latestLat.isFinite() && latestLon.isFinite()) {
                    pushLocation(latestLat, latestLon, latestKm, latestSpeedKmh)
                }
                verifyInteractiveMap(attempt = 0)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    pageReady = false
                    mapVerified = false
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
        startDirectGps()
        post { refreshCourse(force = false) }
    }

    override fun onDetachedFromWindow() {
        stopDirectGps()
        unregisterRideReceiver()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            post {
                refreshCourse(force = false)
                if (!directGpsStarted) startDirectGps()
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun startDirectGps() {
        if (directGpsStarted || !isAttachedToWindow || !hasLocationPermission()) return
        try {
            val intervalMs = if (gpsRateHz == 5) 200L else 1000L
            val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            if (gpsEnabled) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMs,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
                directGpsStarted = true
                primeLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    1000L,
                    0f,
                    this,
                    Looper.getMainLooper()
                )
                directGpsStarted = true
                primeLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: SecurityException) {
            directGpsStarted = false
        } catch (_: Exception) {
            directGpsStarted = false
        }
        updateStatusText()
    }

    private fun stopDirectGps() {
        if (!directGpsStarted) return
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        directGpsStarted = false
    }

    private fun restartDirectGps() {
        stopDirectGps()
        lastFixElapsedMs = 0L
        actualHz = 0.0
        startDirectGps()
    }

    private fun primeLastKnownLocation(provider: String) {
        try {
            val last = locationManager.getLastKnownLocation(provider) ?: return
            val ageMs = System.currentTimeMillis() - last.time
            if (ageMs in 0..300_000L) onLocationChanged(last)
        } catch (_: SecurityException) {}
    }

    override fun onLocationChanged(location: Location) {
        if (location.latitude !in -90.0..90.0 || location.longitude !in -180.0..180.0) return
        if (location.hasAccuracy() && location.accuracy > 100f) return

        val now = SystemClock.elapsedRealtime()
        if (lastFixElapsedMs > 0L && now > lastFixElapsedMs) {
            val hz = 1000.0 / (now - lastFixElapsedMs).toDouble()
            if (hz in 0.05..20.0) actualHz = if (actualHz <= 0.0) hz else actualHz * 0.78 + hz * 0.22
        }
        lastFixElapsedMs = now

        latestLat = location.latitude
        latestLon = location.longitude
        if (location.hasSpeed()) latestSpeedKmh = (location.speed * 3.6).coerceIn(0.0, 120.0)

        activeCourse?.let { c ->
            runCatching { c.nearestRouteLocation(latestLat, latestLon) }.getOrNull()?.let { match ->
                // Only use a nearby GPX projection. If the selected GPX is elsewhere, keep the
                // camera at the real phone position rather than jumping to that course.
                if (match.distanceM <= 250.0) latestKm = match.routeKm
            }
        }

        pushLocation(latestLat, latestLon, latestKm, latestSpeedKmh)
        pushGpsRateToPage()
        updateStatusText()
    }

    override fun onProviderDisabled(provider: String) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

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

    private fun verifyInteractiveMap(attempt: Int) {
        if (!pageReady || !isAttachedToWindow) return
        evaluateJavascript("window.rcMapReady===true") { raw ->
            if (raw == "true") {
                if (!mapVerified) {
                    mapVerified = true
                    animate().alpha(1f).setDuration(180L).start()
                    pushGpsRateToPage()
                    updateStatusText()
                }
            } else if (attempt < 12) {
                postDelayed({ verifyInteractiveMap(attempt + 1) }, 500L)
            } else {
                mapVerified = false
                alpha = 0f
                updateStatus("실시간 내비 지도 로딩 실패 · 카카오맵 미리보기 사용")
            }
        }
    }

    private fun refreshCourse(force: Boolean) {
        if (!pageReady) return
        val meta = runCatching { courseRepo.activeMeta() }.getOrNull() ?: return
        if (!force && activeCourseId == meta.id) return
        val course = runCatching { courseRepo.loadCourse(meta.id) }.getOrNull() ?: return
        activeCourseId = meta.id
        activeCourse = course

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
        updateStatusText()
    }

    private fun pushLocation(lat: Double, lon: Double, km: Double, speedKmh: Double) {
        if (!pageReady || !lat.isFinite() || !lon.isFinite()) return
        val safeKm = if (km.isFinite()) km.coerceAtLeast(0.0) else 0.0
        val safeSpeed = if (speedKmh.isFinite()) speedKmh.coerceIn(0.0, 120.0) else 0.0
        val js = String.format(
            Locale.US,
            "window.rcSetLocation && window.rcSetLocation(%.7f,%.7f,%.3f,%.2f);",
            lat,
            lon,
            safeKm,
            safeSpeed
        )
        evaluateJavascript(js, null)
    }

    private fun pushGpsRateToPage() {
        if (!pageReady) return
        val hz = if (actualHz > 0.0) actualHz.coerceAtMost(20.0) else 0.0
        val js = String.format(
            Locale.US,
            "window.rcSetGpsRate && window.rcSetGpsRate(%d,%.2f);",
            gpsRateHz,
            hz
        )
        evaluateJavascript(js, null)
    }

    private fun updateStatusText() {
        val rateText = if (actualHz > 0.0) {
            "요청 ${gpsRateHz}Hz · 실제 ${String.format(Locale.KOREA, "%.1f", actualHz)}Hz"
        } else {
            "요청 ${gpsRateHz}Hz"
        }
        val gpsText = if (latestLat.isFinite() && latestLon.isFinite()) "현재 위치 추적" else "현재 위치 잡는 중"
        updateStatus("내비 지도 · $gpsText · $rateText")
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
        const val TAG_LIVE_MAP = "ride_live_map_v0331"
        private const val KEY_GPS_RATE_HZ = "gps_rate_hz"
        private const val MAX_ROUTE_POINTS = 900

        private val HTML = """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
              <link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" />
              <style>
                html,body{width:100%;height:100%;margin:0;padding:0;background:#111820;overflow:hidden}
                #map{position:absolute;inset:0;opacity:0;transition:opacity .16s ease;background:#111820}
                #map.gps-ready{opacity:1}
                .maplibregl-ctrl-attrib{font-size:8px!important;opacity:.70}
                #waiting{
                  position:absolute;inset:0;display:flex;align-items:center;justify-content:center;
                  color:#dfe8ef;background:#111820;font:700 14px sans-serif;z-index:4;text-align:center;
                }
                .rider-triangle{
                  width:28px;height:32px;position:relative;filter:drop-shadow(0 2px 4px rgba(0,0,0,.62));
                }
                .rider-triangle:before{
                  content:'';position:absolute;inset:0;background:#ffffff;
                  clip-path:polygon(50% 0,100% 100%,50% 78%,0 100%);
                }
                .rider-triangle:after{
                  content:'';position:absolute;left:4px;right:4px;top:5px;bottom:5px;background:#ff4b2b;
                  clip-path:polygon(50% 0,100% 100%,50% 78%,0 100%);
                }
                .nav-chip{
                  position:absolute;left:8px;top:8px;z-index:7;background:rgba(17,24,32,.84);color:#fff;
                  border-radius:12px;padding:4px 7px;font:700 10px sans-serif;pointer-events:none;
                }
                #gpsRate{
                  position:absolute;right:8px;top:8px;z-index:8;background:rgba(17,24,32,.90);color:#fff;
                  border:1px solid rgba(255,255,255,.35);border-radius:12px;padding:5px 9px;
                  font:700 11px sans-serif;-webkit-tap-highlight-color:transparent;
                }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <div id="waiting">휴대폰 GPS로<br/>현재 위치 잡는 중…</div>
              <div id="chip" class="nav-chip">진행방향 ↑ · 자동줌</div>
              <button id="gpsRate" onclick="RiderGps.toggleGpsRate()">GPS 5Hz</button>
              <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
              <script>
                window.rcMapReady=false;
                let map=null;
                let routePts=[];
                let routeName='';
                let rider=null;
                let firstLocation=true;
                let lastFix=null;
                let smoothHeading=null;
                let latestKm=0;
                let latestSpeed=0;
                let requestedGpsHz=5;
                let actualGpsHz=0;

                const style={
                  version:8,
                  sources:{
                    osm:{
                      type:'raster',
                      tiles:['https://tile.openstreetmap.org/{z}/{x}/{y}.png'],
                      tileSize:256,
                      attribution:'© OpenStreetMap contributors'
                    }
                  },
                  layers:[{id:'osm',type:'raster',source:'osm'}]
                };

                function lineFeature(coords){
                  return {type:'FeatureCollection',features:coords.length>1?[{
                    type:'Feature',properties:{},geometry:{type:'LineString',coordinates:coords}
                  }]:[]};
                }

                function ensureRouteLayer(){
                  if(!map || !map.isStyleLoaded()) return;
                  if(!map.getSource('routeRemaining')){
                    map.addSource('routeRemaining',{type:'geojson',data:lineFeature([])});
                    map.addLayer({id:'routeRemaining',type:'line',source:'routeRemaining',paint:{
                      'line-color':'#29b6f6','line-width':6,'line-opacity':0.97
                    },layout:{'line-cap':'round','line-join':'round'}});
                  }
                }

                function renderRoute(km){
                  if(!map || !map.isStyleLoaded() || !routePts.length) return;
                  ensureRouteLayer();
                  const remain=[];
                  let split=0;
                  while(split<routePts.length && routePts[split][2] <= km+0.02) split++;
                  for(let i=Math.max(0,split-1);i<routePts.length;i++) remain.push([routePts[i][1],routePts[i][0]]);
                  const r=map.getSource('routeRemaining'); if(r) r.setData(lineFeature(remain));
                }

                function toRad(v){return v*Math.PI/180}
                function toDeg(v){return v*180/Math.PI}
                function bearing(a,b){
                  const p1=toRad(a[0]), p2=toRad(b[0]), dl=toRad(b[1]-a[1]);
                  const y=Math.sin(dl)*Math.cos(p2);
                  const x=Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(dl);
                  return (toDeg(Math.atan2(y,x))+360)%360;
                }
                function distM(a,b){
                  const R=6371000,p1=toRad(a[0]),p2=toRad(b[0]),dp=toRad(b[0]-a[0]),dl=toRad(b[1]-a[1]);
                  const q=Math.sin(dp/2)**2+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)**2;
                  return R*2*Math.atan2(Math.sqrt(q),Math.sqrt(1-q));
                }
                function angularBlend(from,to,amount){
                  if(from==null || !isFinite(from)) return to;
                  let d=((to-from+540)%360)-180;
                  return (from+d*amount+360)%360;
                }
                function routeHeading(km){
                  if(routePts.length<2) return null;
                  let i=0;
                  while(i<routePts.length-1 && routePts[i][2] < km) i++;
                  const lookKm=Math.max(.06,Math.min(.24,.06+latestSpeed*.004));
                  let j=i;
                  while(j<routePts.length-1 && routePts[j][2] < km+lookKm) j++;
                  const a=routePts[Math.max(0,i-1)],b=routePts[Math.min(routePts.length-1,Math.max(i+1,j))];
                  if(!a || !b) return null;
                  return bearing([a[0],a[1]],[b[0],b[1]]);
                }
                function zoomForSpeed(s){
                  if(s<4) return 18.2;
                  if(s<10) return 17.8;
                  if(s<18) return 17.3;
                  if(s<28) return 16.8;
                  if(s<40) return 16.3;
                  return 15.8;
                }

                function updateCamera(lat,lon,km,speed){
                  if(!map) return;
                  const pos=[lat,lon];
                  let h=routeHeading(km);
                  if(lastFix){
                    const moved=distM(lastFix,pos);
                    if(speed>=3 && moved>=1.2){
                      const gpsH=bearing(lastFix,pos);
                      h=(h==null)?gpsH:angularBlend(h,gpsH,Math.min(.80,Math.max(.35,speed/40)));
                    }
                  }
                  if(h==null) h=(smoothHeading==null?0:smoothHeading);
                  smoothHeading=angularBlend(smoothHeading,h,speed<3?.14:.36);
                  map.easeTo({
                    center:[lon,lat],zoom:zoomForSpeed(speed),bearing:smoothHeading,pitch:0,
                    duration:firstLocation?0:Math.max(120,1000/requestedGpsHz*.9),offset:[0,66],essential:true
                  });
                  lastFix=pos;
                  firstLocation=false;
                  document.getElementById('chip').textContent=Math.round(speed)+' km/h · 진행방향 ↑ · 자동줌';
                }

                window.rcSetRoute=function(points,name){
                  routePts=Array.isArray(points)?points:[];
                  routeName=name||'';
                  if(!map || !map.isStyleLoaded()) return;
                  renderRoute(latestKm);
                  // Deliberately do NOT fit the whole GPX here. Until a real phone GPS fix arrives
                  // the user sees a neutral waiting screen instead of an unrelated course area.
                };

                window.rcSetGpsRate=function(requested,actual){
                  requestedGpsHz=(requested===1)?1:5;
                  actualGpsHz=isFinite(actual)?Math.max(0,actual):0;
                  document.getElementById('gpsRate').textContent='GPS '+requestedGpsHz+'Hz';
                };

                window.rcSetLocation=function(lat,lon,km,speed){
                  latestKm=isFinite(km)?Math.max(0,km):0;
                  latestSpeed=isFinite(speed)?Math.max(0,speed):0;
                  const pos=[lon,lat];
                  if(!rider){
                    const el=document.createElement('div');el.className='rider-triangle';
                    rider=new maplibregl.Marker({element:el,anchor:'center',rotationAlignment:'viewport'})
                      .setLngLat(pos).addTo(map);
                  }else rider.setLngLat(pos);
                  renderRoute(latestKm);
                  document.getElementById('map').classList.add('gps-ready');
                  document.getElementById('waiting').style.display='none';
                  updateCamera(lat,lon,latestKm,latestSpeed);
                };

                try{
                  map=new maplibregl.Map({
                    container:'map',style:style,center:[127.0,36.0],zoom:16,
                    attributionControl:true,dragRotate:false,pitchWithRotate:false,
                    touchPitch:false,cooperativeGestures:false
                  });
                  map.touchZoomRotate.disableRotation();
                  map.on('load',()=>{
                    ensureRouteLayer();
                    window.rcMapReady=true;
                    if(routePts.length) window.rcSetRoute(routePts,routeName);
                  });
                }catch(e){
                  window.rcMapReady=false;
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
