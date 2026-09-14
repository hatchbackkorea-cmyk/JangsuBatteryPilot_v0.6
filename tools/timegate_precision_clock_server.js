#!/usr/bin/env node
'use strict';

/**
 * TimeGate precision clock endpoint.
 *
 * Runs as a tiny localhost-only HTTP service so Tailscale Serve (or the real RCC server)
 * can expose it at /api/race/clock without coupling timing to race-result writes.
 *
 * The Android app already probes /api/race/clock first and falls back to public NTP when
 * the endpoint is unavailable. This service returns server_time_ms for backward compatibility,
 * plus receive/send timestamps for NTP-style four-timestamp clients.
 */

const http = require('http');

const HOST = process.env.TIMEGATE_CLOCK_HOST || '127.0.0.1';
const PORT = Number(process.env.TIMEGATE_CLOCK_PORT || 8766);
const VERSION = 2;

function clockPayload(receiveWallMs, receiveMonoNs) {
  // Keep the final wall-clock sample as close as possible to socket write time.
  const sendMonoNs = process.hrtime.bigint();
  const sendWallMs = Date.now();
  return {
    ok: true,
    version: VERSION,
    source: 'timegate-race-server',
    server_time_ms: sendWallMs,
    server_receive_ms: receiveWallMs,
    server_send_ms: sendWallMs,
    server_receive_mono_ns: receiveMonoNs.toString(),
    server_send_mono_ns: sendMonoNs.toString(),
    processing_ms: Number(sendMonoNs - receiveMonoNs) / 1e6,
  };
}

function writeClockResponse(req, res) {
  const receiveMonoNs = process.hrtime.bigint();
  const receiveWallMs = Date.now();

  res.statusCode = 200;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0');
  res.setHeader('Pragma', 'no-cache');
  res.setHeader('Expires', '0');
  res.setHeader('X-TimeGate-Clock', `v${VERSION}`);

  const payload = clockPayload(receiveWallMs, receiveMonoNs);
  res.end(JSON.stringify(payload));
}

function handler(req, res) {
  let path = '/';
  try {
    path = new URL(req.url || '/', 'http://127.0.0.1').pathname;
  } catch (_) {}

  if (req.method === 'GET' && (path === '/' || path === '/api/race/clock' || path === '/clock')) {
    return writeClockResponse(req, res);
  }

  if (req.method === 'GET' && path === '/health') {
    res.statusCode = 200;
    res.setHeader('Content-Type', 'application/json; charset=utf-8');
    res.setHeader('Cache-Control', 'no-store');
    return res.end(JSON.stringify({ ok: true, service: 'timegate-precision-clock', version: VERSION }));
  }

  res.statusCode = 404;
  res.setHeader('Content-Type', 'application/json; charset=utf-8');
  res.end(JSON.stringify({ ok: false, error: 'not_found' }));
}

function startServer() {
  const server = http.createServer(handler);
  server.keepAliveTimeout = 5000;
  server.headersTimeout = 6000;
  server.listen(PORT, HOST, () => {
    console.log(`[TimeGate Clock] listening on http://${HOST}:${PORT}`);
    console.log(`[TimeGate Clock] endpoint: /api/race/clock`);
  });
  return server;
}

// Optional drop-in for an existing Express RCC process.
function installExpressRoute(app) {
  if (!app || typeof app.get !== 'function') throw new Error('Express app.get() is required');
  app.get('/api/race/clock', (req, res) => {
    const receiveMonoNs = process.hrtime.bigint();
    const receiveWallMs = Date.now();
    res.set('Cache-Control', 'no-store, no-cache, must-revalidate, max-age=0');
    res.set('Pragma', 'no-cache');
    res.set('Expires', '0');
    res.set('X-TimeGate-Clock', `v${VERSION}`);
    res.json(clockPayload(receiveWallMs, receiveMonoNs));
  });
}

if (require.main === module) startServer();

module.exports = { startServer, installExpressRoute, handler };
