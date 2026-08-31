// Jangsu Ride Copilot - minimal group ride relay
// Node.js 18+ / no external packages. For real granfondo use behind HTTPS.
// Start: node group_relay_server.js
// Env: PORT=8787, GROUP_TTL_MS=120000
const http = require('http');
const { URL } = require('url');
const PORT = Number(process.env.PORT || 8787);
const TTL = Number(process.env.GROUP_TTL_MS || 120000);
const MAX_RIDERS = Number(process.env.GROUP_MAX_RIDERS || 20);
const rooms = new Map();

function send(res, code, body) {
  res.writeHead(code, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});
  res.end(JSON.stringify(body));
}
function clean(room) {
  const now = Date.now();
  const m = rooms.get(room);
  if (!m) return;
  for (const [id, r] of m) if (now - Number(r.serverSeenMs || 0) > TTL) m.delete(id);
  if (!m.size) rooms.delete(room);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let s=''; req.on('data', c => { s += c; if (s.length > 65536) req.destroy(); });
    req.on('end', () => { try { resolve(JSON.parse(s || '{}')); } catch(e) { reject(e); } });
    req.on('error', reject);
  });
}
const server = http.createServer(async (req,res) => {
  const u = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (req.method === 'POST' && u.pathname === '/update') {
    try {
      const b = await readBody(req);
      const room = String(b.room || '').trim().slice(0,64);
      const riderId = String(b.riderId || '').trim().slice(0,96);
      if (!room || !riderId) return send(res,400,{error:'room and riderId required'});
      const m = rooms.get(room) || new Map();
      rooms.set(room,m);
      clean(room);
      const active = rooms.get(room) || new Map();
      if (!active.has(riderId) && active.size >= MAX_RIDERS) {
        return send(res,409,{error:'room_full',maxRiders:MAX_RIDERS});
      }
      rooms.set(room, active);
      active.set(riderId, {
        riderId,
        nickname:String(b.nickname || 'rider').slice(0,40),
        courseKey:String(b.courseKey || '').slice(0,64),
        routeKm:Number(b.routeKm || 0),
        lat:Number(b.lat || 0), lon:Number(b.lon || 0),
        speedKph:Number(b.speedKph || 0),
        updatedMs:Number(b.updatedMs || Date.now()),
        serverSeenMs:Date.now()
      });
      clean(room);
      return send(res,200,{ok:true,maxRiders:MAX_RIDERS});
    } catch(e) { return send(res,400,{error:'bad json'}); }
  }
  if (req.method === 'GET' && u.pathname === '/room') {
    const room = String(u.searchParams.get('room') || '').trim().slice(0,64);
    if (!room) return send(res,400,{error:'room required'});
    clean(room);
    const riders = [...(rooms.get(room)?.values() || [])].map(({serverSeenMs,...r}) => r);
    return send(res,200,{room,riders,maxRiders:MAX_RIDERS});
  }
  if (req.method === 'GET' && u.pathname === '/health') return send(res,200,{ok:true,rooms:rooms.size,maxRiders:MAX_RIDERS});
  return send(res,404,{error:'not found'});
});
server.listen(PORT, () => console.log(`group relay listening on ${PORT}`));
