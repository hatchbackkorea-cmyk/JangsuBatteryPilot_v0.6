package com.seungjae.jangsu280battery

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Read-only participant view of the same field RACE broadcast page used on the big screen. */
class RaceBroadcastActivity : Activity() {
    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = RaceServerClient(this)
        val store = RaceDataStore(this)
        val eventCode = intent.getStringExtra(EXTRA_EVENT_CODE).orEmpty().trim().uppercase()
            .ifBlank { store.lastJoined()?.config?.eventCode.orEmpty() }
        val baseUrl = intent.getStringExtra(EXTRA_SERVER_URL).orEmpty().trim().trimEnd('/')
            .ifBlank { client.baseUrl().trim().trimEnd('/') }
        if (eventCode.isBlank() || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            Toast.makeText(this, "참가 중인 대회 또는 현장 서버를 확인해 주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 8, 13))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            setBackgroundColor(Color.rgb(18, 25, 36))
        }
        top.addView(Button(this).apply {
            text = "‹ START 화면"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(126), dp(48)))
        top.addView(TextView(this).apply {
            text = "실시간 중계 · $eventCode"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        top.addView(Button(this).apply {
            text = "디버그"
            isAllCaps = false
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { showAiDebug(eventCode) }
        }, LinearLayout.LayoutParams(dp(82), dp(48)))
        root.addView(top)

        val web = WebView(this).apply {
            setBackgroundColor(Color.rgb(5, 8, 13))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(
                        "document.querySelectorAll('.qrBox').forEach(function(e){e.style.display='none'});void(0);",
                        null
                    )
                    ensureAiPlayer(view)
                }
            }
        }
        webView = web
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val encoded = URLEncoder.encode(eventCode, "UTF-8")
        web.loadUrl("$baseUrl/race-live/$encoded?participant=1")
    }

    /**
     * The server normally injects race_ai_commentary.js into the live page. Some field
     * deployments can still serve the plain race_live.html route, so the participant WebView
     * guarantees that the audio player exists. Without this player the commentary feed is not
     * polled at all, which also means START/CP/FINISH TTS is never scheduled.
     */
    private fun ensureAiPlayer(view: WebView?) {
        val js = """
            (function bootTimeGateAi(){
              try{
                if(typeof window.__raceAiEnqueue==='function'){
                  window.__tgAiPlayerBoot='already-loaded';
                  return;
                }
                var old=document.getElementById('tg-ai-player-script');
                if(old){
                  window.__tgAiPlayerBoot='loading';
                  return;
                }
                var s=document.createElement('script');
                s.id='tg-ai-player-script';
                s.src='/static/race_ai_commentary.js?app=0.34.85&ts='+Date.now();
                s.async=false;
                s.onload=function(){window.__tgAiPlayerBoot=(typeof window.__raceAiEnqueue==='function')?'loaded':'loaded-no-player';};
                s.onerror=function(){
                  window.__tgAiPlayerBoot='load-error';
                  try{s.remove();}catch(e){}
                  setTimeout(bootTimeGateAi,1200);
                };
                (document.head||document.documentElement).appendChild(s);
                window.__tgAiPlayerBoot='injected';
              }catch(e){window.__tgAiPlayerBoot='exception:'+String(e);}
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun showAiDebug(eventCode: String) {
        val encoded = URLEncoder.encode(eventCode, "UTF-8")
        val js = """
            (async function(){
              var old=document.getElementById('tg-ai-debug');
              if(old){old.remove();return;}
              var box=document.createElement('div');
              box.id='tg-ai-debug';
              box.style.cssText='position:fixed;z-index:2147483647;left:10px;right:10px;top:10px;bottom:10px;background:#07101af2;color:#eef6ff;border:1px solid #3e6ea8;border-radius:12px;padding:12px;font:13px/1.45 monospace;overflow:auto;text-align:left;box-shadow:0 10px 40px #000c';
              box.innerHTML='<div style="display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:8px"><b style="font:900 16px system-ui">TimeGate AI 중계 디버그</b><button id="tgDbgClose" style="padding:6px 10px">닫기</button></div><div id="tgDbgOut">조회 중...</div><div style="display:flex;gap:8px;flex-wrap:wrap;margin-top:10px"><button id="tgDbgPlay" style="padding:8px 10px">🔊 최신 음성 재생 테스트</button><button id="tgDbgReload" style="padding:8px 10px">↻ 다시 조회</button></div><div id="tgDbgPlayState" style="margin-top:8px;color:#9fc5ff"></div>';
              document.body.appendChild(box);
              document.getElementById('tgDbgClose').onclick=function(){box.remove();};
              async function read(){
                var lines=[];
                var relay={
                  app_version:'0.34.85',
                  player_loaded:(typeof window.__raceAiEnqueue==='function'),
                  player_boot:(window.__tgAiPlayerBoot||'-'),
                  script_tag:!!document.querySelector('script[src*="race_ai_commentary.js"]'),
                  visibility:document.visibilityState,
                  ai_feed:{http:0,enabled:false,ready:false,pending:0,last_error:'',items:0},
                  announcer:{http:0,items:0},
                  live:{http:0,phase:'',active:0}
                };
                lines.push('event = $encoded');
                lines.push('page = '+location.href);
                lines.push('AI player loaded = '+relay.player_loaded);
                lines.push('AI player boot = '+relay.player_boot);
                lines.push('AI script tag = '+relay.script_tag);
                lines.push('visibility = '+document.visibilityState);
                lines.push('userAgent = '+navigator.userAgent);
                try{
                  var r=await fetch('/api/race/ai-commentary/$encoded/feed?after=0',{cache:'no-store'});
                  var x=await r.json();
                  window.__tgAiDebugFeed=x;
                  var items=Array.isArray(x.items)?x.items:[];
                  relay.ai_feed={http:r.status,enabled:!!x.enabled,ready:!!x.ready,pending:Number(x.pending||0),last_error:String(x.last_error||''),items:items.length};
                  lines.push('');
                  lines.push('[AI FEED] HTTP '+r.status);
                  lines.push('enabled = '+x.enabled+' / ready = '+x.ready+' / pending = '+x.pending);
                  lines.push('last_error = '+(x.last_error||'-'));
                  lines.push('items = '+items.length);
                  items.slice(-8).forEach(function(v){
                    lines.push('#'+(v.id||'?')+' '+(v.event_type||'?')+' role='+(v.role||'?')+' source='+(v.source||'?')+' exp='+(v.expires_at_ms||'-')+' audio='+(v.audio_url||'-'));
                  });
                }catch(e){relay.ai_feed.last_error='client:'+String(e);lines.push('AI feed ERROR = '+e);}
                try{
                  var sr=await fetch('/api/race/session-announcer/$encoded/feed?after=0',{cache:'no-store'});
                  var sx=await sr.json();
                  relay.announcer={http:sr.status,items:Array.isArray(sx.items)?sx.items.length:0};
                  lines.push('');
                  lines.push('[ANNOUNCER] HTTP '+sr.status+' / items = '+relay.announcer.items);
                }catch(e){lines.push('ANNOUNCER ERROR = '+e);}
                try{
                  var lr=await fetch('/api/race/live/$encoded',{cache:'no-store'});
                  var lx=await lr.json();
                  relay.live={http:lr.status,phase:String(lx.phase||lx.event?.phase||''),active:Array.isArray(lx.active)?lx.active.length:0};
                  lines.push('');
                  lines.push('[LIVE] HTTP '+lr.status+' / phase = '+(relay.live.phase||'-')+' / active = '+relay.live.active);
                }catch(e){lines.push('LIVE ERROR = '+e);}
                try{
                  var dr=await fetch('/api/race/debug-relay/$encoded',{
                    method:'POST',
                    cache:'no-store',
                    headers:{'Content-Type':'application/json'},
                    body:JSON.stringify(relay)
                  });
                  var dx=await dr.json();
                  window.__tgAiDebugRelay=dx;
                  lines.push('');
                  lines.push('[AUTO RELAY] HTTP '+dr.status+' / published = '+!!dx.published);
                  lines.push('branch = '+(dx.branch||'-')+' / file = '+(dx.file||'-'));
                  if(dx.commit)lines.push('commit = '+dx.commit);
                  if(dx.error)lines.push('relay_error = '+dx.error);
                }catch(e){
                  lines.push('');
                  lines.push('[AUTO RELAY] ERROR = '+e);
                }
                document.getElementById('tgDbgOut').textContent=lines.join('\n');
              }
              document.getElementById('tgDbgReload').onclick=read;
              document.getElementById('tgDbgPlay').onclick=async function(){
                var s=document.getElementById('tgDbgPlayState');
                var x=window.__tgAiDebugFeed||{};
                var items=Array.isArray(x.items)?x.items:[];
                var item=items.length?items[items.length-1]:null;
                if(!item||!item.audio_url){s.textContent='재생할 AI 음성 항목이 없습니다.';return;}
                try{
                  var a=new Audio(item.audio_url+'?debug='+Date.now());
                  a.volume=1;
                  await a.play();
                  s.textContent='재생 성공 · '+(item.event_type||'')+' #'+(item.id||'');
                }catch(e){s.textContent='재생 실패 · '+(e&&e.name?e.name:'Error')+' · '+(e&&e.message?e.message:String(e));}
              };
              await read();
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    override fun onDestroy() {
        webView?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        webView = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        const val EXTRA_EVENT_CODE = "event_code"
        const val EXTRA_SERVER_URL = "server_url"
    }
}
