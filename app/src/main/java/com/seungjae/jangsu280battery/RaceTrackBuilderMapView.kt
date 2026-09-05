package com.seungjae.jangsu280battery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

/** Lightweight MapLibre/CyclOSM editor preview for recorded RACE tracks and traps. */
@SuppressLint("SetJavaScriptEnabled")
class RaceTrackBuilderMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {
    private var ready = false
    private var pendingScript: String? = null

    init {
        setBackgroundColor(Color.rgb(17, 24, 32))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pendingScript?.let { evaluateJavascript(it, null) }
                pendingScript = null
            }
        }
        loadDataWithBaseURL("https://race-track.local/", HTML, "text/html", "UTF-8", null)
    }

    fun render(points: List<RaceTrackDraftStore.Point>, gates: List<RaceGate>, selectedRouteM: Double?, followLatest: Boolean) {
        val coords = JSONArray().apply { points.forEach { p -> put(JSONArray().put(p.lon).put(p.lat)) } }
        val features = JSONArray().apply {
            gates.forEach { g ->
                put(JSONObject().apply {
                    put("type", "Feature")
                    put("geometry", JSONObject().apply {
                        put("type", "Point"); put("coordinates", JSONArray().put(g.lon).put(g.lat))
                    })
                    put("properties", JSONObject().apply {
                        put("name", g.name); put("gateType", g.type); put("routeM", g.routeM)
                    })
                })
            }
        }
        val selected = selectedRouteM?.let { m -> points.minByOrNull { kotlin.math.abs(it.routeM - m) } }
        val selectedJs = if (selected == null) "null" else "[${selected.lon},${selected.lat}]"
        val script = "window.renderRaceTrack(${coords},${features},$selectedJs,${if (followLatest) "true" else "false"});"
        if (ready) evaluateJavascript(script, null) else pendingScript = script
    }

    companion object {
        private val HTML = """
            <!doctype html><html><head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" />
            <style>
              html,body,#map{margin:0;width:100%;height:100%;background:#111820} .maplibregl-ctrl-attrib{font-size:8px!important;opacity:.6}
              .gateLabel{font:800 12px system-ui;color:#fff;background:#111c;border:2px solid #fff;border-radius:5px;padding:2px 5px;white-space:nowrap}
            </style></head><body><div id="map"></div>
            <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
            <script>
              const map=new maplibregl.Map({container:'map',center:[127.27,36.99],zoom:15,style:{version:8,sources:{osm:{type:'raster',tiles:['https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenStreetMap · CyclOSM'}},layers:[{id:'osm',type:'raster',source:'osm'}]}});
              map.addControl(new maplibregl.NavigationControl({showCompass:true}),'top-right');
              let loaded=false,gateMarkers=[],cursor=null,lastLen=0;
              map.on('load',()=>{loaded=true; map.addSource('track',{type:'geojson',data:{type:'Feature',geometry:{type:'LineString',coordinates:[]}}}); map.addLayer({id:'track',type:'line',source:'track',paint:{'line-color':'#29b6f6','line-width':6,'line-outline-color':'#ffffff'}});});
              function clearGates(){gateMarkers.forEach(m=>m.remove());gateMarkers=[];if(cursor){cursor.remove();cursor=null;}}
              window.renderRaceTrack=function(coords,features,selected,follow){
                if(!loaded){setTimeout(()=>window.renderRaceTrack(coords,features,selected,follow),200);return;}
                map.getSource('track').setData({type:'Feature',geometry:{type:'LineString',coordinates:coords}}); clearGates();
                (features||[]).forEach(f=>{const p=f.properties||{},el=document.createElement('div');el.className='gateLabel';el.textContent=(p.gateType==='START'?'▶ ':p.gateType==='FINISH'?'■ ':'◆ ')+(p.name||''); gateMarkers.push(new maplibregl.Marker({element:el,anchor:'bottom'}).setLngLat(f.geometry.coordinates).addTo(map));});
                if(selected){const el=document.createElement('div');el.style.cssText='width:14px;height:14px;border-radius:50%;background:#ffeb3b;border:3px solid #000';cursor=new maplibregl.Marker({element:el}).setLngLat(selected).addTo(map);}
                if(coords&&coords.length){
                  if(follow){map.easeTo({center:coords[coords.length-1],zoom:17,duration:350});}
                  else if(lastLen===0||Math.abs(coords.length-lastLen)>100){let b=new maplibregl.LngLatBounds(coords[0],coords[0]);coords.forEach(c=>b.extend(c));map.fitBounds(b,{padding:45,maxZoom:17,duration:350});}
                  lastLen=coords.length;
                }
              };
            </script></body></html>
        """.trimIndent()
    }
}
