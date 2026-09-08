package com.seungjae.jangsu280battery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

data class RaceCpSuggestion(
    val routeM: Double,
    val lat: Double,
    val lon: Double,
    val bearingDeg: Double,
    val speedDropRatio: Double,
    val turnDeg: Double,
    val score: Double
)

/** MapLibre/CyclOSM preview + free-geometry editor for recorded TimeGate tracks and gates. */
@SuppressLint("SetJavaScriptEnabled")
class RaceTrackBuilderMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : WebView(context, attrs) {

    fun interface TrapEditListener {
        fun onTrapChanged(index: Int, lat: Double, lon: Double, bearingDeg: Double, widthM: Double)
    }

    fun interface SuggestionTapListener {
        fun onSuggestionSelected(index: Int)
    }

    private var ready = false
    private var pendingScript: String? = null
    private var trapEditListener: TrapEditListener? = null
    private var suggestionTapListener: SuggestionTapListener? = null
    private var editable = false

    private inner class RaceEditorBridge {
        @JavascriptInterface
        fun onTrapChanged(index: Int, lat: Double, lon: Double, bearingDeg: Double, widthM: Double) {
            post {
                trapEditListener?.onTrapChanged(
                    index,
                    lat.coerceIn(-90.0, 90.0),
                    lon.coerceIn(-180.0, 180.0),
                    ((bearingDeg % 360.0) + 360.0) % 360.0,
                    widthM.coerceIn(1.0, 20.0)
                )
            }
        }

        @JavascriptInterface
        fun onSuggestionSelected(index: Int) {
            post { suggestionTapListener?.onSuggestionSelected(index) }
        }
    }

    init {
        setBackgroundColor(Color.rgb(17, 24, 32))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        addJavascriptInterface(RaceEditorBridge(), "AndroidRaceEditor")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                pendingScript?.let { evaluateJavascript(it, null) }
                pendingScript = null
                applyEditMode()
            }
        }
        loadDataWithBaseURL("https://race-track.local/", HTML, "text/html", "UTF-8", null)
    }

    fun setTrapEditListener(listener: TrapEditListener?, enabled: Boolean = listener != null) {
        trapEditListener = listener
        editable = enabled
        applyEditMode()
    }

    fun setSuggestionTapListener(listener: SuggestionTapListener?) {
        suggestionTapListener = listener
    }

    private fun applyEditMode() {
        val script = "window.setRaceTrackEditMode && window.setRaceTrackEditMode(${if (editable) "true" else "false"});"
        if (ready) evaluateJavascript(script, null) else pendingScript = script
    }

    fun render(
        points: List<RaceTrackDraftStore.Point>,
        gates: List<RaceGate>,
        selectedRouteM: Double?,
        followLatest: Boolean,
        suggestions: List<RaceCpSuggestion> = emptyList(),
        selectedSuggestionIndex: Int = -1,
        recommendedGateKeys: Set<Int> = emptySet()
    ) {
        val coords = JSONArray().apply { points.forEach { p -> put(JSONArray().put(p.lon).put(p.lat)) } }
        val features = JSONArray().apply {
            gates.forEachIndexed { index, g ->
                put(JSONObject().apply {
                    put("type", "Feature")
                    put("geometry", JSONObject().apply { put("type", "Point"); put("coordinates", JSONArray().put(g.lon).put(g.lat)) })
                    put("properties", JSONObject().apply {
                        put("index", index); put("name", g.name); put("gateType", g.type); put("routeM", g.routeM)
                        put("bearingDeg", g.bearingDeg); put("widthM", g.widthM)
                        put("recommendedSource", g.type == "SECTOR" && recommendedGateKeys.contains(g.routeM.roundToInt()))
                    })
                })
            }
        }
        val suggestionFeatures = JSONArray().apply {
            suggestions.forEachIndexed { index, s ->
                put(JSONObject().apply {
                    put("type", "Feature")
                    put("geometry", JSONObject().apply { put("type", "Point"); put("coordinates", JSONArray().put(s.lon).put(s.lat)) })
                    put("properties", JSONObject().apply {
                        put("index", index); put("routeM", s.routeM); put("bearingDeg", s.bearingDeg)
                        put("dropPct", s.speedDropRatio * 100.0); put("turnDeg", s.turnDeg); put("score", s.score)
                        put("selected", index == selectedSuggestionIndex)
                    })
                })
            }
        }
        val selected = selectedRouteM?.let { m -> points.minByOrNull { kotlin.math.abs(it.routeM - m) } }
        val selectedJs = if (selected == null) "null" else "[${selected.lon},${selected.lat}]"
        val heading = points.lastOrNull()?.bearingDeg?.takeIf { it.isFinite() }
        val headingJs = heading?.toString() ?: "null"
        val script = "window.renderRaceTrack(${coords},${features},${suggestionFeatures},$selectedJs,${if (followLatest) "true" else "false"},$headingJs);window.setRaceTrackEditMode(${if (editable) "true" else "false"});"
        if (ready) evaluateJavascript(script, null) else pendingScript = script
    }

    companion object {
        private val HTML = """
            <!doctype html><html><head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css" />
            <style>
              html,body,#map{margin:0;width:100%;height:100%;background:#111820;overflow:hidden}.maplibregl-ctrl-attrib{font-size:8px!important;opacity:.6}
              .gateWrap{position:relative;width:132px;height:132px;cursor:pointer;user-select:none;touch-action:none;filter:drop-shadow(0 2px 3px #000a)}
              .gateVector{position:absolute;left:66px;top:66px;width:0;height:0;transform-origin:0 0;pointer-events:none}
              .gateShaft{position:absolute;left:-4px;top:-43px;width:8px;height:43px;border-radius:5px;background:var(--gate);box-shadow:0 0 0 2px #fff9}
              .gateTip{position:absolute;left:-12px;top:-60px;width:0;height:0;border-left:12px solid transparent;border-right:12px solid transparent;border-bottom:0;border-top:20px solid var(--gate);filter:drop-shadow(0 0 1px #fff)}
              .gateLabel{position:absolute;left:76px;top:72px;font:900 12px system-ui;color:#fff;background:#111e;border:2px solid var(--gate);border-radius:6px;padding:2px 5px;white-space:nowrap;pointer-events:none}
              .gateCenter{position:absolute;left:58px;top:58px;width:16px;height:16px;border-radius:50%;background:var(--gate);border:2px solid #fff;box-sizing:border-box;pointer-events:none}
              .gateHit{position:absolute;left:42px;top:42px;width:48px;height:48px;border-radius:50%;background:transparent}
              .directionHandle,.widthHandle{display:none;position:absolute;border-radius:50%;background:#fff;border:4px solid var(--gate);width:18px;height:18px;box-sizing:border-box;pointer-events:auto}
              .directionHandle{left:-9px;top:-72px;cursor:grab}.widthLine{display:none;position:absolute;top:-2px;height:4px;background:#fff;border-radius:2px;opacity:.9;pointer-events:none}.widthHandle{top:-9px;cursor:ew-resize}
              .gateWrap.selected .directionHandle,.gateWrap.selected .widthHandle,.gateWrap.selected .widthLine{display:block}.gateWrap.selected .gateCenter{box-shadow:0 0 0 5px #ffffff66}
              .suggestion{position:relative;width:104px;height:54px;cursor:pointer;user-select:none;filter:drop-shadow(0 2px 4px #000a)}
              .suggestionDot{position:absolute;left:40px;top:13px;width:26px;height:26px;border-radius:50%;border:4px dashed #c06cff;background:#35144dcc;color:white;font:900 17px/18px system-ui;text-align:center;padding-top:1px;box-sizing:border-box}
              .suggestionLabel{position:absolute;left:69px;top:14px;color:#f1d9ff;background:#23102edd;border:2px solid #c06cff;border-radius:6px;padding:3px 5px;font:900 10px system-ui;white-space:nowrap}
              .suggestion.selected .suggestionDot{border-style:solid;background:#c06cff;color:#16051d;box-shadow:0 0 0 5px #fff8}.suggestion.selected .suggestionLabel{background:#c06cff;color:#16051d}
              .editHint{position:absolute;left:10px;bottom:14px;z-index:5;background:#07101add;color:#eaf2ff;border:1px solid #45617d;border-radius:9px;padding:7px 9px;font:800 11px system-ui;display:none;pointer-events:none}.editHint.on{display:block}
            </style></head><body><div id="map"></div><div id="editHint" class="editHint">실선 CP=적용됨 · 보라 ★=추천 후보 · 화살표 드래그=위치/방향/폭</div>
            <script src="https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js"></script>
            <script>
              const map=new maplibregl.Map({container:'map',center:[127.27,36.99],zoom:15,bearingSnap:0,style:{version:8,sources:{osm:{type:'raster',tiles:['https://a.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenStreetMap · CyclOSM'}},layers:[{id:'osm',type:'raster',source:'osm'}]}});
              map.addControl(new maplibregl.NavigationControl({showCompass:true}),'top-right');
              let loaded=false,gateMarkers=[],suggestionMarkers=[],cursor=null,lastLen=0,lastCoords=[],lastFeatures=[],lastSuggestions=[],editMode=false,selectedIndex=-1,gesture=null;
              map.on('load',()=>{loaded=true;map.addSource('track',{type:'geojson',data:{type:'Feature',geometry:{type:'LineString',coordinates:[]}}});map.addLayer({id:'track',type:'line',source:'track',paint:{'line-color':'#29b6f6','line-width':6}});});
              const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));const norm=v=>((Number(v||0)%360)+360)%360;
              function colorFor(t,recommended){if(t==='START')return'#55d56b';if(t==='FINISH')return'#ff5964';return recommended?'#36b8ff':'#ffd24f'}
              function hav(a,b){const R=6371000,r=x=>x*Math.PI/180,dlat=r(b.lat-a.lat),dlon=r(b.lng-a.lng),la1=r(a.lat),la2=r(b.lat);const s=Math.sin(dlat/2)**2+Math.cos(la1)*Math.cos(la2)*Math.sin(dlon/2)**2;return 2*R*Math.atan2(Math.sqrt(s),Math.sqrt(Math.max(0,1-s)))}
              function routeBearing(a,b){const r=x=>x*Math.PI/180,d=x=>x*180/Math.PI,p1=r(a[1]),p2=r(b[1]),dl=r(b[0]-a[0]),y=Math.sin(dl)*Math.cos(p2),x=Math.cos(p1)*Math.sin(p2)-Math.sin(p1)*Math.cos(p2)*Math.cos(dl);return norm(d(Math.atan2(y,x)))}
              function emit(state){try{AndroidRaceEditor.onTrapChanged(Number(state.index),Number(state.lat),Number(state.lon),norm(state.bearing),clamp(Number(state.width),1,20))}catch(e){}}
              function clearMarkers(){gateMarkers.forEach(x=>x.marker.remove());gateMarkers=[];suggestionMarkers.forEach(x=>x.remove());suggestionMarkers=[];if(cursor){cursor.remove();cursor=null;}}
              function visual(rec){const s=rec.state,el=rec.el,vec=el.querySelector('.gateVector'),line=el.querySelector('.widthLine'),wh=el.querySelector('.widthHandle');vec.style.transform='rotate('+norm(s.bearing)+'deg)';const span=12+clamp(Number(s.width),1,20)*1.6;line.style.left=(-span)+'px';line.style.width=(span*2)+'px';wh.style.left=(span-9)+'px';}
              function selectGate(index){selectedIndex=index;gateMarkers.forEach(rec=>{rec.el.classList.toggle('selected',rec.state.index===selectedIndex);visual(rec)});}
              function beginHandle(e,rec,role){if(!editMode)return;e.preventDefault();e.stopPropagation();selectGate(rec.state.index);gesture={rec,role,pid:e.pointerId};map.dragPan.disable();try{e.target.setPointerCapture(e.pointerId)}catch(_){}}
              function moveHandle(e){if(!gesture||gesture.pid!==e.pointerId)return;e.preventDefault();const rec=gesture.rec,rect=map.getContainer().getBoundingClientRect(),px=e.clientX-rect.left,py=e.clientY-rect.top,center=map.project(rec.marker.getLngLat());if(gesture.role==='direction'){const dx=px-center.x,dy=py-center.y;rec.state.bearing=norm(Math.atan2(dx,-dy)*180/Math.PI)}else{const ll=map.unproject([px,py]);rec.state.width=clamp(hav(rec.marker.getLngLat(),ll)*2,1,20)}visual(rec);}
              function endHandle(e){if(!gesture||gesture.pid!==e.pointerId)return;const rec=gesture.rec;gesture=null;map.dragPan.enable();emit(rec.state)}
              document.addEventListener('pointermove',moveHandle,{passive:false});document.addEventListener('pointerup',endHandle,{passive:false});document.addEventListener('pointercancel',endHandle,{passive:false});
              function makeGate(f){const p=f.properties||{},idx=Number(p.index||0),typ=String(p.gateType||'SECTOR'),recommended=!!p.recommendedSource,c=colorFor(typ,recommended),el=document.createElement('div');el.className='gateWrap';el.style.setProperty('--gate',c);const prefix=typ==='START'?'▶ ':typ==='FINISH'?'■ ':recommended?'◆R ':'◆ ';el.innerHTML='<div class="gateVector"><div class="gateShaft"></div><div class="gateTip"></div><div class="widthLine"></div><div class="widthHandle"></div><div class="directionHandle"></div></div><div class="gateCenter"></div><div class="gateHit"></div><div class="gateLabel">'+prefix+String(p.name||'')+'</div>';const state={index:idx,lat:Number(f.geometry.coordinates[1]),lon:Number(f.geometry.coordinates[0]),bearing:norm(p.bearingDeg),width:clamp(Number(p.widthM||5),1,20)};const marker=new maplibregl.Marker({element:el,anchor:'center',draggable:editMode}).setLngLat([state.lon,state.lat]).addTo(map),rec={marker,el,state};marker.on('dragstart',()=>selectGate(idx));marker.on('dragend',()=>{const ll=marker.getLngLat();state.lat=ll.lat;state.lon=ll.lng;emit(state)});el.addEventListener('pointerdown',e=>{if(!e.target.classList.contains('directionHandle')&&!e.target.classList.contains('widthHandle'))selectGate(idx)});el.querySelector('.directionHandle').addEventListener('pointerdown',e=>beginHandle(e,rec,'direction'));el.querySelector('.widthHandle').addEventListener('pointerdown',e=>beginHandle(e,rec,'width'));visual(rec);return rec;}
              function makeSuggestion(f){const p=f.properties||{},idx=Number(p.index||0),el=document.createElement('div');el.className='suggestion'+(p.selected?' selected':'');el.innerHTML='<div class="suggestionDot">★</div><div class="suggestionLabel">추천 '+(idx+1)+'</div>';el.addEventListener('click',e=>{e.preventDefault();e.stopPropagation();try{AndroidRaceEditor.onSuggestionSelected(idx)}catch(_){}});return new maplibregl.Marker({element:el,anchor:'center'}).setLngLat(f.geometry.coordinates).addTo(map);}
              function redraw(){clearMarkers();(lastFeatures||[]).forEach(f=>gateMarkers.push(makeGate(f)));(lastSuggestions||[]).forEach(f=>suggestionMarkers.push(makeSuggestion(f)));if(selectedIndex<0&&gateMarkers.length)selectedIndex=gateMarkers[0].state.index;selectGate(selectedIndex);}
              window.setRaceTrackEditMode=function(on){editMode=!!on;document.getElementById('editHint').classList.toggle('on',editMode);if(loaded)redraw();};
              window.renderRaceTrack=function(coords,features,suggestions,selected,follow,heading){
                if(!loaded){setTimeout(()=>window.renderRaceTrack(coords,features,suggestions,selected,follow,heading),200);return;}
                lastCoords=coords||[];lastFeatures=features||[];lastSuggestions=suggestions||[];map.getSource('track').setData({type:'Feature',geometry:{type:'LineString',coordinates:lastCoords}});redraw();
                if(!editMode&&selected){const el=document.createElement('div');el.style.cssText='width:14px;height:14px;border-radius:50%;background:#ffeb3b;border:3px solid #000';cursor=new maplibregl.Marker({element:el}).setLngLat(selected).addTo(map);}
                if(lastCoords.length){
                  if(follow){let h=Number(heading);if(!Number.isFinite(h)&&lastCoords.length>1)h=routeBearing(lastCoords[lastCoords.length-2],lastCoords[lastCoords.length-1]);map.easeTo({center:lastCoords[lastCoords.length-1],zoom:17,bearing:Number.isFinite(h)?h:map.getBearing(),duration:350});}
                  else if(lastLen===0||Math.abs(lastCoords.length-lastLen)>100){let b=new maplibregl.LngLatBounds(lastCoords[0],lastCoords[0]);lastCoords.forEach(c=>b.extend(c));map.fitBounds(b,{padding:45,maxZoom:17,duration:350});}
                  lastLen=lastCoords.length;
                }
              };
            </script></body></html>
        """.trimIndent()
    }
}
