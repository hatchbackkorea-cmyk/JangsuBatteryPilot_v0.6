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
 * MTB HUD live navigation map.
 *
 * v0.33.0 turns the v0.32.9 whole-route preview into a rider-centred navigation camera:
 * - the rider stays near the lower centre of the map
 * - travel direction is always up (heading-up)
 * - zoom changes automatically with speed
 * - the already ridden route is deliberately de-emphasised
 * - the selected GPX remains the navigation line
 *
 * The existing Kakao static-map ImageView stays underneath this view as a fallback.
 * GPS is still consumed from the existing RideService broadcast, so this view does not
 * create a second location subscription.
 */
@SuppressLint("SetJavaScriptEnabled")
class RideLiveMapWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    private val courseRepo = CourseRepository(context)
    private var pageReady = false
    private var mapVerified = false
    private var receiverRegistered = false
    private var activeCourseId: String? = null
    private var latestLat = Double.NaN
    private var latestLon = Double.NaN
    private var latestKm = 0.0
    private var latestSpeedKmh = 0.0

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
            latestSpeedKmh = intent.getDoubleExtra(RideService.EXTRA_SPEED_KMH, latestSpeedKmh)
            if (latestLat.isFinite() && latestLon.isFinite()) {
                pushLocation(latestLat, latestLon, latestKm, latestSpeedKmh)
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
                mapVerified = false
                refreshCourse(force = true)
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

    private fun verifyInteractiveMap(attempt: Int) {
        if (!pageReady || !isAttachedToWindow) return
        evaluateJavascript("window.rcMapReady===true") { raw ->
            if (raw == "true") {
                if (!mapVerified) {
                    mapVerified = true
                    animate().alpha(1f).setDuration(180L).start()
                    updateStatus("내비 지도 · 진행방향 ↑ · 속도 자동줌 · GPS 대기")
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
        updateStatus("내비 지도 · ${meta.name} · GPS 대기")
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
        if (mapVerified) {
            updateStatus(
                "내비 지도 · 진행방향 ↑ · ${String.format(Locale.KOREA, "%.0f", safeSpeed)} km/h · 자동줌"
            )
        }
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
        private const val MAX_ROUTE_POINTS = 900

        private val HTML = """
            <!doctype html>
            <html>
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
              <link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" />
              <style>
                html,body,#map{width:100%;height:100%;margin:0;padding:0;background:#111820;overflow:hidden}
                .maplibregl-ctrl-attrib{font-size:8px!important;opacity:.70}
                .rider-arrow{
                  width:24px;height:24px;border-radius:50%;background:#ff4b2b;border:3px solid #fff;
                  box-shadow:0 1px 7px rgba(0,0,0,.55);position:relative;box-sizing:border-box;
                }
                .rider-arrow:before{
                  content:'';position:absolute;left:50%;top:-10px;transform:translateX(-50%);
                  width:0;height:0;border-left:7px solid transparent;border-right:7px solid transparent;
                  border-bottom:12px solid #fff;
                }
                .nav-chip{
                  position:absolute;left:8px;top:8px;z-index:5;background:rgba(17,24,32,.82);color:#fff;
                  border-radius:12px;padding:4px 7px;font:700 10px sans-serif;pointer-events:none;
                }
              </style>
            </head>
            <body>
              <div id="map"></div>
              <div id="chip" class="nav-chip">GPS 대기 · 진행방향 ↑</div>
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

                function ensureRouteLayers(){
                  if(!map || !map.isStyleLoaded()) return;
                  if(!map.getSource('routeDone')){
                    map.addSource('routeDone',{type:'geojson',data:lineFeature([])});
                    map.addLayer({id:'routeDone',type:'line',source:'routeDone',paint:{
                      'line-color':'#75818b','line-width':2,'line-opacity':0.16
                    },layout:{'line-cap':'round','line-join':'round'}});
                  }
                  if(!map.getSource('routeRemaining')){
                    map.addSource('routeRemaining',{type:'geojson',data:lineFeature([])});
                    map.addLayer({id:'routeRemaining',type:'line',source:'routeRemaining',paint:{
                      'line-color':'#29b6f6','line-width':6,'line-opacity':0.96
                    },layout:{'line-cap':'round','line-join':'round'}});
                  }
                }

                function renderRoute(km){
                  if(!map || !map.isStyleLoaded() || !routePts.length) return;
                  ensureRouteLayers();
                  const done=[];
                  const remain=[];
                  let split=0;
                  while(split<routePts.length && routePts[split][2] <= km+0.02) split++;
                  for(let i=0;i<Math.min(routePts.length,split+1);i++) done.push([routePts[i][1],routePts[i][0]]);
                  for(let i=Math.max(0,split-1);i<routePts.length;i++) remain.push([routePts[i][1],routePts[i][0]]);
                  const d=map.getSource('routeDone'); if(d) d.setData(lineFeature(done));
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
                  const R=6371000, p1=toRad(a[0]),p2=toRad(b[0]),dp=toRad(b[0]-a[0]),dl=toRad(b[1]-a[1]);
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
                  const lookKm=Math.max(0.07,Math.min(0.22,0.07+latestSpeed*0.0035));
                  let j=i;
                  while(j<routePts.length-1 && routePts[j][2] < km+lookKm) j++;
                  const a=routePts[Math.max(0,i-1)], b=routePts[Math.max(i+1,j)];
                  if(!a || !b) return null;
                  return bearing([a[0],a[1]],[b[0],b[1]]);
                }
                function zoomForSpeed(s){
                  if(s<4) return 17.9;
                  if(s<10) return 17.55;
                  if(s<18) return 17.05;
                  if(s<28) return 16.55;
                  if(s<40) return 16.05;
                  return 15.55;
                }

                function updateCamera(lat,lon,km,speed){
                  if(!map) return;
                  const pos=[lat,lon];
                  let h=routeHeading(km);
                  if(lastFix){
                    const moved=distM(lastFix,pos);
                    if(speed>=5 && moved>=3){
                      const gpsH=bearing(lastFix,pos);
                      h=(h==null)?gpsH:angularBlend(h,gpsH,Math.min(.72,Math.max(.35,speed/45)));
                    }
                  }
                  if(h==null) h=(smoothHeading==null?0:smoothHeading);
                  smoothHeading=angularBlend(smoothHeading,h,speed<4?.18:.34);
                  const z=zoomForSpeed(speed);
                  map.easeTo({
                    center:[lon,lat],zoom:z,bearing:smoothHeading,pitch:0,
                    duration:firstLocation?0:420,offset:[0,58],essential:true
                  });
                  lastFix=pos;
                  firstLocation=false;
                  document.getElementById('chip').textContent=Math.round(speed)+' km/h · 진행방향 ↑ · 자동줌';
                }

                window.rcSetRoute=function(points,name){
                  routePts=Array.isArray(points)?points:[];
                  routeName=name||'';
                  latestKm=0;
                  if(!map || !map.isStyleLoaded()) return;
                  renderRoute(latestKm);
                  if(firstLocation && routePts.length>1){
                    const bounds=new maplibregl.LngLatBounds();
                    routePts.forEach(p=>bounds.extend([p[1],p[0]]));
                    map.fitBounds(bounds,{padding:24,duration:0,maxZoom:15.5});
                  }
                };

                window.rcSetLocation=function(lat,lon,km,speed){
                  latestKm=isFinite(km)?Math.max(0,km):0;
                  latestSpeed=isFinite(speed)?Math.max(0,speed):0;
                  const pos=[lon,lat];
                  if(!rider){
                    const el=document.createElement('div');el.className='rider-arrow';
                    rider=new maplibregl.Marker({element:el,anchor:'center',rotationAlignment:'viewport'})
                      .setLngLat(pos).addTo(map);
                  }else rider.setLngLat(pos);
                  renderRoute(latestKm);
                  updateCamera(lat,lon,latestKm,latestSpeed);
                };

                try{
                  map=new maplibregl.Map({
                    container:'map',style:style,center:[127.0,36.0],zoom:14,
                    attributionControl:true,dragRotate:false,pitchWithRotate:false,
                    touchPitch:false,cooperativeGestures:false
                  });
                  map.touchZoomRotate.disableRotation();
                  map.on('load',()=>{
                    ensureRouteLayers();
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
