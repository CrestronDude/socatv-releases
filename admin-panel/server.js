'use strict';
const express    = require('express');
const crypto     = require('crypto');
const fs         = require('fs');
const path       = require('path');
const http       = require('http');
const https      = require('https');
const multer     = require('multer');

// Load .env if present (GITHUB_TOKEN etc.)
const envPath = path.join(__dirname, '.env');
if (fs.existsSync(envPath)) {
  fs.readFileSync(envPath, 'utf8').split('\n').forEach(line => {
    const m = line.match(/^\s*([^#=\s]+)\s*=\s*(.*)$/);
    if (m) process.env[m[1]] = m[2].trim();
  });
}

const app  = express();
const PORT = 7890;
const DB   = path.join(__dirname, 'nova_data.json');

// ── SSE: real-time push to admin panel clients ────────────────────────────────
const sseClients = new Set();
function sseEmit(event, data) {
  const msg = `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const res of sseClients) { try { res.write(msg); } catch(_){} }
}

app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

// ── CORS: allow any origin (devices on LAN / internet can check in) ──────────
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Headers', 'Content-Type, X-Api-Key');
  res.header('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(200);
  next();
});

// ── Database ──────────────────────────────────────────────────────────────────
const DEFAULT_DB = {
  devices:  {},   // keyed by deviceId
  licenses: {},   // keyed by licenseKey
  groups:   {},   // keyed by groupId
  blacklist: {    // mac/ip/androidId/deviceId blacklists
    deviceIds:  [],
    androidIds: [],
    macAddresses: [],
    ipAddresses: [],
    entries: []   // full history with reason + timestamp
  },
  activity: [],   // array of events (newest first, capped at 2000)
  config: {
    trialDays:        10,
    priceLabel:       '$10 / year',
    paymentUrl:       '',
    activationEnabled: true,
    appEnabled:       true,
    announcement:     '',
    minVersionCode:   1,
    forceUpdateUrl:   '',
    reportingUrl:     '',
    features: {
      qrLogin:      true,
      m3uImport:    true,
      xtreamImport: true,
      multiscreen:  true,
      epg:          true
    }
  },
  settings: {
    licenseSecret:  'pShzo7G5Y1zoFPfwuUC3KEn6ixzqWycz',
    checkinApiKey:       'NovaTv-Checkin-K3y-2024',
    adminToken:          '',
    githubToken:         process.env.GITHUB_TOKEN || '',   // set in .env — never hardcoded
    gistId:              '01b5363984ee9c951219ff8feaafae19',
    githubReleasesRepo:  'CrestronDude/socatv-releases',
    configSigningSecret: 'N0v@C0nfigSig#2024!K3y',        // must match SIG_KEY in RemoteConfigManager.kt
    revenue:        0,
    notes:          '',
    smtpUser:       '',
    smtpPass:       '',
    lemonWebhookSecret: '',
    emailFrom:      'SocaTV Nova <noreply@socatv.app>'
  },
  configHistory: []
};

function readDb() {
  try   { return JSON.parse(fs.readFileSync(DB, 'utf8')); }
  catch { return JSON.parse(JSON.stringify(DEFAULT_DB)); }
}

function writeDb(data) {
  fs.writeFileSync(DB, JSON.stringify(data, null, 2));
}

function log(db, deviceId, type, data = {}) {
  db.activity.unshift({ id: Date.now() + Math.random(), deviceId, type, data, ts: Date.now() });
  if (db.activity.length > 2000) db.activity.length = 2000;
}

// ── License helpers ───────────────────────────────────────────────────────────
function makeHmac(payload, secret) {
  return crypto.createHmac('sha256', secret).update(payload).digest('hex');
}

function generateKey(expiryDateStr, secret) {
  const payload = `SOCANOVA-${expiryDateStr}`;
  const sig = makeHmac(payload, secret).slice(0, 16).toUpperCase();
  return `SOCANOVA-${expiryDateStr}-${sig}`;
}

function validateKey(key, secret) {
  const parts = (key || '').trim().toUpperCase().split('-');
  if (parts.length !== 3 || parts[0] !== 'SOCANOVA') return { valid: false, reason: 'bad_format' };
  const dateStr = parts[1];
  const sig     = parts[2];
  if (!/^\d{8}$/.test(dateStr)) return { valid: false, reason: 'bad_date' };
  const expected = makeHmac(`SOCANOVA-${dateStr}`, secret).slice(0, 16).toUpperCase();
  if (sig !== expected) return { valid: false, reason: 'bad_hmac' };
  const y = +dateStr.slice(0,4), m = +dateStr.slice(4,6)-1, d = +dateStr.slice(6,8);
  const expiry = new Date(y, m, d, 23, 59, 59);
  if (expiry < new Date()) return { valid: false, reason: 'expired', expiry: expiry.getTime() };
  return { valid: true, expiry: expiry.getTime() };
}

// ── IP geolocation (free, no key needed, 45 req/min) ─────────────────────────
function geoIp(ip) {
  return new Promise(resolve => {
    if (!ip || ip === '127.0.0.1' || ip === '::1') return resolve({ country: 'Local', city: 'Localhost', region: '' });
    const url = `http://ip-api.com/json/${ip}?fields=country,regionName,city,lat,lon,isp`;
    http.get(url, res => {
      let body = '';
      res.on('data', d => body += d);
      res.on('end', () => { try { resolve(JSON.parse(body)); } catch { resolve({}); } });
    }).on('error', () => resolve({}));
  });
}

function getClientIp(req) {
  return (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();
}

// ── SSE endpoint — admin panel subscribes here for live events ───────────────
app.get('/api/admin/events', (req, res) => {
  res.setHeader('Content-Type',  'text/event-stream');
  res.setHeader('Cache-Control', 'no-cache');
  res.setHeader('Connection',    'keep-alive');
  res.flushHeaders();
  res.write(`event: connected\ndata: ${JSON.stringify({ ts: Date.now() })}\n\n`);
  sseClients.add(res);
  req.on('close', () => sseClients.delete(res));
});

// ════════════════════════════════════════════════════════════════════════════
//  PUBLIC API — apps call these endpoints
// ════════════════════════════════════════════════════════════════════════════

// Device check-in (called by app on each launch)
app.post('/api/checkin', async (req, res) => {
  const db = readDb();
  const { deviceId, androidId, macAddress, model, androidVersion, appVersionCode,
          trialStartTs, licenseKey, status, apiKey, brand } = req.body;

  if (!deviceId) return res.status(400).json({ ok: false, error: 'missing deviceId' });

  // Soft auth — reject unknown API keys
  if (db.settings.checkinApiKey && apiKey !== db.settings.checkinApiKey) {
    return res.status(401).json({ ok: false, error: 'unauthorized' });
  }

  const ip  = getClientIp(req);
  const geo = await geoIp(ip);
  const now = Date.now();

  // ── Blacklist check (before storing anything) ─────────────────────────────
  const bl = db.blacklist || { deviceIds:[], androidIds:[], macAddresses:[], ipAddresses:[], entries:[] };
  const blockedEntry =
    (bl.deviceIds.includes(deviceId))           ? bl.entries.find(e=>e.value===deviceId) :
    (androidId && bl.androidIds.includes(androidId)) ? bl.entries.find(e=>e.value===androidId) :
    (macAddress && macAddress !== 'unknown' && bl.macAddresses.includes(macAddress)) ? bl.entries.find(e=>e.value===macAddress) :
    (bl.ipAddresses.includes(ip))               ? bl.entries.find(e=>e.value===ip) :
    null;

  if (blockedEntry) {
    log(db, deviceId, 'blocked_checkin', { ip, mac: macAddress, androidId, reason: blockedEntry.reason });
    writeDb(db);
    sseEmit('blocked', { deviceId, reason: blockedEntry.reason, ts: Date.now() });
    return res.json({
      ok: false,
      blocked: true,
      blockReason: blockedEntry.reason || 'This device has been blocked by the administrator.'
    });
  }

  // ── Update device record ──────────────────────────────────────────────────
  const isNew = !db.devices[deviceId];
  db.devices[deviceId] = {
    ...(db.devices[deviceId] || {}),
    deviceId,
    androidId:      androidId  || db.devices[deviceId]?.androidId  || '',
    macAddress:     (macAddress && macAddress !== 'unknown') ? macAddress : (db.devices[deviceId]?.macAddress || ''),
    model:          model || 'Unknown',
    brand:          brand || '',
    androidVersion: androidVersion || '',
    appVersionCode: appVersionCode || 0,
    trialStartTs:   trialStartTs || (db.devices[deviceId]?.trialStartTs) || now,
    licenseKey:     licenseKey || (db.devices[deviceId]?.licenseKey) || '',
    status:         status || 'trial',
    lastIp:         ip,
    country:        geo.country || '',
    region:         geo.regionName || '',
    city:           geo.city || '',
    lat:            geo.lat,
    lon:            geo.lon,
    isp:            geo.isp || '',
    lastSeen:       now,
    firstSeen:      db.devices[deviceId]?.firstSeen || now,
    groupId:        db.devices[deviceId]?.groupId || '',
    notes:          db.devices[deviceId]?.notes   || '',
    paywallHits:    (db.devices[deviceId]?.paywallHits || 0) + (status === 'expired' ? 1 : 0),
    checkinCount:   (db.devices[deviceId]?.checkinCount || 0) + 1
  };

  log(db, deviceId, isNew ? 'new_device' : 'checkin', { ip, country: geo.country, status });

  const dev = db.devices[deviceId];
  writeDb(db);

  // Push real-time event to any open admin panel tabs
  sseEmit('checkin', {
    deviceId, model: dev.model, status: dev.status,
    country: dev.country, city: dev.city,
    appVersionCode: dev.appVersionCode, isNew,
    ts: Date.now()
  });

  res.json({
    ok:           true,
    blocked:      false,
    config:       db.config,
    trialDays:    dev.customTrialDays || db.config.trialDays,
    adminMessage: dev.adminMessage || ''
  });
});

// ── Blacklist Management API ───────────────────────────────────────────────────

// Get full blacklist
app.get('/api/admin/blacklist', (req, res) => {
  const db = readDb();
  res.json(db.blacklist || { deviceIds:[], androidIds:[], macAddresses:[], ipAddresses:[], entries:[] });
});

// Add to blacklist
app.post('/api/admin/blacklist', (req, res) => {
  const db = readDb();
  if (!db.blacklist) db.blacklist = { deviceIds:[], androidIds:[], macAddresses:[], ipAddresses:[], entries:[] };
  const { type, value, reason } = req.body; // type: 'deviceId'|'androidId'|'macAddress'|'ipAddress'
  if (!type || !value) return res.status(400).json({ error: 'type and value required' });

  const listKey = type === 'deviceId'    ? 'deviceIds' :
                  type === 'androidId'   ? 'androidIds' :
                  type === 'macAddress'  ? 'macAddresses' :
                  type === 'ipAddress'   ? 'ipAddresses' : null;
  if (!listKey) return res.status(400).json({ error: 'invalid type' });

  if (!db.blacklist[listKey].includes(value)) db.blacklist[listKey].push(value);

  // Remove duplicate entries for same value, then add fresh
  db.blacklist.entries = db.blacklist.entries.filter(e => !(e.type === type && e.value === value));
  db.blacklist.entries.unshift({ type, value, reason: reason || '', addedAt: Date.now() });

  // Mark device as blocked if we have it
  if (type === 'deviceId' && db.devices[value]) {
    db.devices[value].blocked = true;
    db.devices[value].blockReason = reason || '';
  }

  log(db, 'admin', 'blacklisted', { type, value, reason });
  writeDb(db);
  res.json({ ok: true });
});

// Remove from blacklist
app.delete('/api/admin/blacklist', (req, res) => {
  const db = readDb();
  if (!db.blacklist) return res.json({ ok: true });
  const { type, value } = req.body;
  if (!type || !value) return res.status(400).json({ error: 'type and value required' });

  const listKey = type === 'deviceId' ? 'deviceIds' : type === 'androidId' ? 'androidIds' :
                  type === 'macAddress' ? 'macAddresses' : type === 'ipAddress' ? 'ipAddresses' : null;
  if (listKey) db.blacklist[listKey] = db.blacklist[listKey].filter(v => v !== value);
  db.blacklist.entries = db.blacklist.entries.filter(e => !(e.type === type && e.value === value));

  if (type === 'deviceId' && db.devices[value]) {
    db.devices[value].blocked = false;
    db.devices[value].blockReason = '';
  }

  log(db, 'admin', 'unblacklisted', { type, value });
  writeDb(db);
  res.json({ ok: true });
});

// License validation (app can call to double-check; also works offline via HMAC)
app.get('/api/license/validate/:key', (req, res) => {
  const db     = readDb();
  const key    = req.params.key;
  const result = validateKey(key, db.settings.licenseSecret);

  // Check if revoked
  const stored = db.licenses[key];
  if (stored?.revoked) return res.json({ valid: false, reason: 'revoked' });

  res.json(result);
});

// Remote config endpoint (app can fetch directly from admin server)
app.get('/api/remote-config', (req, res) => {
  const db = readDb();
  res.json(db.config);
});

// ════════════════════════════════════════════════════════════════════════════
//  ADMIN API — panel calls these
// ════════════════════════════════════════════════════════════════════════════

// Stats overview
app.get('/api/admin/stats', (req, res) => {
  const db = readDb();
  const devices = Object.values(db.devices);
  const licenses = Object.values(db.licenses);
  const now = Date.now();
  const day = 86_400_000;

  res.json({
    totalDevices:    devices.length,
    activeToday:     devices.filter(d => now - d.lastSeen < day).length,
    activeThisWeek:  devices.filter(d => now - d.lastSeen < 7*day).length,
    trialDevices:    devices.filter(d => d.status === 'trial').length,
    licensedDevices: devices.filter(d => d.status === 'licensed').length,
    expiredDevices:  devices.filter(d => d.status === 'expired').length,
    newToday:        devices.filter(d => now - d.firstSeen < day).length,
    newThisWeek:     devices.filter(d => now - d.firstSeen < 7*day).length,
    totalLicenses:   licenses.length,
    activeLicenses:  licenses.filter(d => !d.revoked && d.expiryTs > now).length,
    expiredLicenses: licenses.filter(d => !d.revoked && d.expiryTs < now).length,
    unusedLicenses:  licenses.filter(d => !d.revoked && !d.deviceId).length,
    revokedLicenses: licenses.filter(d => d.revoked).length,
    revenue:         db.settings.revenue || 0,
    countries:       [...new Set(devices.map(d => d.country).filter(Boolean))].length,
    recentActivity:  db.activity.slice(0, 20)
  });
});

// All devices
app.get('/api/admin/devices', (req, res) => {
  const db = readDb();
  res.json(Object.values(db.devices).sort((a,b) => b.lastSeen - a.lastSeen));
});

// Single device
app.get('/api/admin/devices/:id', (req, res) => {
  const db = readDb();
  const d  = db.devices[req.params.id];
  if (!d) return res.status(404).json({ error: 'not found' });
  const history = db.activity.filter(e => e.deviceId === req.params.id);
  res.json({ ...d, history });
});

// Update device (notes, group, trial extension, force status, admin message)
app.put('/api/admin/devices/:id', (req, res) => {
  const db = readDb();
  const id = req.params.id;
  if (!db.devices[id]) return res.status(404).json({ error: 'not found' });
  const { notes, groupId, customTrialDays, forcedStatus, adminMessage, licenseKey } = req.body;
  if (notes         !== undefined) db.devices[id].notes          = notes;
  if (groupId       !== undefined) db.devices[id].groupId        = groupId;
  if (customTrialDays !== undefined) db.devices[id].customTrialDays = customTrialDays;
  if (forcedStatus  !== undefined) db.devices[id].status         = forcedStatus;
  if (adminMessage  !== undefined) db.devices[id].adminMessage   = adminMessage;
  if (licenseKey    !== undefined) db.devices[id].licenseKey     = licenseKey;
  log(db, id, 'admin_edit', req.body);
  writeDb(db);
  res.json({ ok: true, device: db.devices[id] });
});

// Extend device trial
app.post('/api/admin/devices/:id/extend-trial', (req, res) => {
  const db = readDb();
  const id = req.params.id;
  if (!db.devices[id]) return res.status(404).json({ error: 'not found' });
  const { extraDays } = req.body;
  db.devices[id].customTrialDays = (db.devices[id].customTrialDays || db.config.trialDays) + (extraDays || 7);
  log(db, id, 'trial_extended', { extraDays });
  writeDb(db);
  res.json({ ok: true, customTrialDays: db.devices[id].customTrialDays });
});

// Delete device record
app.delete('/api/admin/devices/:id', (req, res) => {
  const db = readDb();
  delete db.devices[req.params.id];
  log(db, req.params.id, 'device_deleted');
  writeDb(db);
  res.json({ ok: true });
});

// Bulk device actions
app.post('/api/admin/devices/bulk', (req, res) => {
  const db = readDb();
  const { ids, action, value } = req.body;
  const results = [];
  for (const id of (ids || [])) {
    if (!db.devices[id]) continue;
    switch (action) {
      case 'extend_trial': db.devices[id].customTrialDays = (db.devices[id].customTrialDays || db.config.trialDays) + (value || 7); break;
      case 'set_status':   db.devices[id].status = value; break;
      case 'set_group':    db.devices[id].groupId = value; break;
      case 'delete':       delete db.devices[id]; break;
    }
    log(db, id, `bulk_${action}`, { value });
    results.push(id);
  }
  writeDb(db);
  res.json({ ok: true, affected: results.length });
});

// ── Licenses ──────────────────────────────────────────────────────────────────

app.get('/api/admin/licenses', (req, res) => {
  const db = readDb();
  res.json(Object.values(db.licenses).sort((a,b) => b.createdAt - a.createdAt));
});

app.post('/api/admin/licenses/generate', (req, res) => {
  const db = readDb();
  const { expiryDate, count = 1, notes = '', assignDeviceId = '' } = req.body;
  if (!expiryDate || !/^\d{8}$/.test(expiryDate)) return res.status(400).json({ error: 'invalid expiryDate (YYYYMMDD)' });

  const keys = [];
  for (let i = 0; i < Math.min(count, 500); i++) {
    // Each key is unique even with same date — append random nonce to HMAC input when bulk
    const nonce  = count > 1 ? `-${crypto.randomBytes(4).toString('hex').toUpperCase()}` : '';
    const base   = `SOCANOVA-${expiryDate}${nonce}`;
    const sig    = makeHmac(base, db.settings.licenseSecret).slice(0, 16).toUpperCase();
    const key    = count > 1
      ? `SOCANOVA-${expiryDate}-${sig}` // Note: for bulk each has unique HMAC (nonce in secret)
      : generateKey(expiryDate, db.settings.licenseSecret);

    const expiry = new Date(+expiryDate.slice(0,4), +expiryDate.slice(4,6)-1, +expiryDate.slice(6,8), 23,59,59);
    const entry  = {
      key, notes,
      createdAt: Date.now(),
      expiryDate,
      expiryTs:  expiry.getTime(),
      deviceId:  assignDeviceId || null,
      activatedAt: assignDeviceId ? Date.now() : null,
      revoked:   false
    };
    db.licenses[key] = entry;
    keys.push(entry);
    if (assignDeviceId && db.devices[assignDeviceId]) {
      db.devices[assignDeviceId].licenseKey = key;
      db.devices[assignDeviceId].status     = 'licensed';
    }
    log(db, assignDeviceId || 'admin', 'license_generated', { key, expiryDate });
  }
  writeDb(db);
  res.json({ ok: true, keys });
});

// Extend license expiry
app.put('/api/admin/licenses/:key/extend', (req, res) => {
  const db  = readDb();
  const key = decodeURIComponent(req.params.key);
  const lic = db.licenses[key];
  if (!lic) return res.status(404).json({ error: 'not found' });

  const { newExpiryDate } = req.body; // YYYYMMDD
  if (!newExpiryDate || !/^\d{8}$/.test(newExpiryDate)) return res.status(400).json({ error: 'invalid date' });

  // Generate a new key with the new expiry date (old key still works until old expiry)
  const newKey  = generateKey(newExpiryDate, db.settings.licenseSecret);
  const expiry  = new Date(+newExpiryDate.slice(0,4), +newExpiryDate.slice(4,6)-1, +newExpiryDate.slice(6,8), 23,59,59);

  db.licenses[newKey] = {
    key: newKey, notes: `Extended from ${key}`,
    createdAt: Date.now(), expiryDate: newExpiryDate, expiryTs: expiry.getTime(),
    deviceId: lic.deviceId, activatedAt: lic.activatedAt, revoked: false
  };

  // Update the device record if assigned
  if (lic.deviceId && db.devices[lic.deviceId]) {
    db.devices[lic.deviceId].licenseKey = newKey;
    db.devices[lic.deviceId].status     = 'licensed';
  }

  // Optionally revoke old key
  if (req.body.revokeOld) lic.revoked = true;

  log(db, lic.deviceId || 'admin', 'license_extended', { oldKey: key, newKey, newExpiryDate });
  writeDb(db);
  res.json({ ok: true, newKey, newEntry: db.licenses[newKey] });
});

// Revoke license
app.put('/api/admin/licenses/:key/revoke', (req, res) => {
  const db  = readDb();
  const key = decodeURIComponent(req.params.key);
  if (!db.licenses[key]) return res.status(404).json({ error: 'not found' });
  db.licenses[key].revoked    = true;
  db.licenses[key].revokedAt  = Date.now();
  db.licenses[key].revokedNote = req.body.reason || '';
  if (db.licenses[key].deviceId && db.devices[db.licenses[key].deviceId]) {
    db.devices[db.licenses[key].deviceId].status = 'revoked';
  }
  log(db, 'admin', 'license_revoked', { key });
  writeDb(db);
  res.json({ ok: true });
});

app.delete('/api/admin/licenses/:key', (req, res) => {
  const db  = readDb();
  const key = decodeURIComponent(req.params.key);
  delete db.licenses[key];
  writeDb(db);
  res.json({ ok: true });
});

// ── Groups ────────────────────────────────────────────────────────────────────

app.get('/api/admin/groups', (req, res) => {
  const db = readDb();
  res.json(Object.values(db.groups));
});

app.post('/api/admin/groups', (req, res) => {
  const db = readDb();
  const id = 'grp_' + Date.now();
  db.groups[id] = { id, name: req.body.name || 'Unnamed', color: req.body.color || '#00DCFF', description: req.body.description || '', createdAt: Date.now() };
  writeDb(db);
  res.json({ ok: true, group: db.groups[id] });
});

app.put('/api/admin/groups/:id', (req, res) => {
  const db = readDb();
  if (!db.groups[req.params.id]) return res.status(404).json({ error: 'not found' });
  Object.assign(db.groups[req.params.id], req.body);
  writeDb(db);
  res.json({ ok: true });
});

app.delete('/api/admin/groups/:id', (req, res) => {
  const db = readDb();
  delete db.groups[req.params.id];
  // Unassign devices from this group
  for (const d of Object.values(db.devices)) {
    if (d.groupId === req.params.id) d.groupId = '';
  }
  writeDb(db);
  res.json({ ok: true });
});

// ── Remote Config ─────────────────────────────────────────────────────────────

app.get('/api/admin/config', (req, res) => {
  const db = readDb();
  res.json(db.config);
});

app.put('/api/admin/config', (req, res) => {
  const db = readDb();
  // Save history entry
  db.configHistory.unshift({ snapshot: JSON.parse(JSON.stringify(db.config)), savedAt: Date.now() });
  if (db.configHistory.length > 20) db.configHistory.length = 20;
  Object.assign(db.config, req.body);
  log(db, 'admin', 'config_updated', { changes: Object.keys(req.body) });
  writeDb(db);
  res.json({ ok: true, config: db.config });
});

app.get('/api/admin/config/history', (req, res) => {
  const db = readDb();
  res.json(db.configHistory);
});

// ── Activity Log ──────────────────────────────────────────────────────────────

app.get('/api/admin/activity', (req, res) => {
  const db    = readDb();
  const limit = Math.min(+(req.query.limit) || 200, 1000);
  res.json(db.activity.slice(0, limit));
});

// ── Settings ──────────────────────────────────────────────────────────────────

app.get('/api/admin/settings', (req, res) => {
  const db = readDb();
  // Never expose the full secret — mask it
  const s = { ...db.settings };
  s.licenseSecret = s.licenseSecret ? s.licenseSecret.slice(0,3) + '***' + s.licenseSecret.slice(-3) : '';
  res.json(s);
});

app.put('/api/admin/settings', (req, res) => {
  const db = readDb();
  const allowed = ['checkinApiKey','adminToken','githubToken','gistId','githubReleasesRepo','revenue','notes','reportingUrl','smtpUser','smtpPass','lemonWebhookSecret','emailFrom'];
  for (const k of allowed) { if (req.body[k] !== undefined) db.settings[k] = req.body[k]; }
  // Secret key requires confirmation
  if (req.body.licenseSecret && req.body.confirmSecret === req.body.licenseSecret) {
    db.settings.licenseSecret = req.body.licenseSecret;
  }
  writeDb(db);
  res.json({ ok: true });
});

// Revenue update
app.post('/api/admin/settings/revenue', (req, res) => {
  const db = readDb();
  db.settings.revenue = (db.settings.revenue || 0) + (req.body.amount || 0);
  log(db, 'admin', 'revenue_recorded', { amount: req.body.amount, note: req.body.note });
  writeDb(db);
  res.json({ ok: true, revenue: db.settings.revenue });
});

// DB backup
app.get('/api/admin/backup', (req, res) => {
  const data = fs.readFileSync(DB, 'utf8');
  res.setHeader('Content-Disposition', `attachment; filename="nova_backup_${Date.now()}.json"`);
  res.setHeader('Content-Type', 'application/json');
  res.send(data);
});

// DB restore
app.post('/api/admin/restore', (req, res) => {
  try {
    const data = JSON.stringify(req.body);
    JSON.parse(data); // validate
    fs.writeFileSync(DB, data);
    res.json({ ok: true });
  } catch (e) {
    res.status(400).json({ error: 'invalid backup file' });
  }
});

// Validate a key manually
app.post('/api/admin/licenses/validate', (req, res) => {
  const db     = readDb();
  const result = validateKey(req.body.key, db.settings.licenseSecret);
  const stored = db.licenses[req.body.key];
  res.json({ ...result, revoked: stored?.revoked || false, stored: stored || null });
});

// Compute HMAC sig for the config (same fields / format as RemoteConfigManager.kt)
function signConfig(config, secret) {
  const fields = ['appEnabled', 'checkinApiKey', 'forceUpdate', 'latestVersionCode', 'trialDays'];
  const payload = fields.sort().map(k => `${k}=${config[k]}`).join('|');
  return crypto.createHmac('sha256', secret).update(payload).digest('hex');
}

// Push config to GitHub Gist
app.post('/api/admin/config/push-gist', async (req, res) => {
  const db = readDb();
  const githubToken = db.settings.githubToken || process.env.GITHUB_TOKEN;
  const { gistId, configSigningSecret } = db.settings;
  if (!githubToken || !gistId) return res.status(400).json({ error: 'githubToken and gistId required in settings' });

  // Always inject the checkinApiKey and sign before pushing
  const configToSign = { ...db.config, checkinApiKey: db.settings.checkinApiKey };
  const sig = signConfig(configToSign, configSigningSecret || 'N0v@C0nfigSig#2024!K3y');
  const signedConfig = { ...configToSign, sig };

  const body = JSON.stringify({
    files: { 'nova_config.json': { content: JSON.stringify(signedConfig, null, 2) } }
  });

  const options = {
    hostname: 'api.github.com',
    path: `/gists/${gistId}`,
    method: 'PATCH',
    headers: {
      'Authorization': `token ${githubToken}`,
      'User-Agent':    'SocaTV-Admin',
      'Content-Type':  'application/json',
      'Content-Length': Buffer.byteLength(body)
    }
  };

  const request = https.request(options, r => {
    let d = '';
    r.on('data', chunk => d += chunk);
    r.on('end', () => {
      if (r.statusCode === 200) {
        log(db, 'admin', 'config_pushed_gist', {});
        writeDb(db);
        res.json({ ok: true });
      } else {
        res.status(r.statusCode).json({ error: d });
      }
    });
  });
  request.on('error', e => res.status(500).json({ error: e.message }));
  request.write(body);
  request.end();
});

// ── Automated Payment Webhook ─────────────────────────────────────────────────
// Lemon Squeezy (and generic) webhook: auto-generates + emails a license key
// Configure in Settings: smtpUser (Gmail), smtpPass (App Password), lemonWebhookSecret

function sendKeyEmail(toEmail, toName, licenseKey, expiry, settings) {
  return new Promise((resolve, reject) => {
    // Use Gmail SMTP via raw SMTP (nodemailer optional) or direct TLS
    try {
      const nodemailer = require('nodemailer');
      const transporter = nodemailer.createTransport({
        service: 'gmail',
        auth: { user: settings.smtpUser, pass: settings.smtpPass }
      });
      const html = `
        <div style="font-family:sans-serif;max-width:520px;margin:0 auto;background:#080810;color:#fff;padding:32px;border-radius:12px">
          <h1 style="color:#00DCFF;margin:0 0 8px">Welcome to SocaTV Nova!</h1>
          <p style="color:#aaa;margin:0 0 24px">Hi ${toName||'there'}, your payment was received. Here is your license key:</p>
          <div style="background:#141426;border:1px solid rgba(0,220,255,.3);border-radius:8px;padding:16px 20px;font-family:monospace;font-size:16px;color:#00DCFF;letter-spacing:1px;word-break:break-all;margin-bottom:24px">${licenseKey}</div>
          <p style="color:#aaa;margin:0 0 8px"><strong style="color:#fff">Valid until:</strong> ${expiry}</p>
          <p style="color:#aaa;margin:0 0 8px">To activate:</p>
          <ol style="color:#aaa;margin:0 0 24px;padding-left:20px">
            <li>Open SocaTV Nova on your TV</li>
            <li>On the paywall screen, press <strong style="color:#fff">Activate License</strong></li>
            <li>Enter the key above exactly</li>
          </ol>
          <p style="color:#555;font-size:11px">Keep this email safe — this key is tied to your purchase. Need help? Reply to this email.</p>
        </div>`;
      transporter.sendMail({
        from: settings.emailFrom || 'SocaTV Nova <noreply@socatv.app>',
        to:   toEmail,
        subject: '🔑 Your SocaTV Nova License Key',
        html
      }, (err) => err ? reject(err) : resolve());
    } catch (e) {
      reject(new Error('nodemailer not installed — run: npm install nodemailer'));
    }
  });
}

// Lemon Squeezy webhook
app.post('/api/webhook/lemonsqueezy', express.raw({ type: 'application/json' }), async (req, res) => {
  const db = readDb();
  const sig    = req.headers['x-signature'];
  const secret = db.settings.lemonWebhookSecret;

  // Verify signature if secret is set
  if (secret) {
    const expected = crypto.createHmac('sha256', secret).update(req.body).digest('hex');
    if (sig !== expected) return res.status(401).json({ error: 'invalid signature' });
  }

  let payload;
  try { payload = JSON.parse(req.body.toString()); }
  catch { return res.status(400).json({ error: 'bad json' }); }

  const eventName = payload.meta?.event_name || '';
  if (!['order_created','subscription_created','subscription_payment_success'].includes(eventName)) {
    return res.json({ ok: true, skipped: true });
  }

  const attrs     = payload.data?.attributes || {};
  const email     = attrs.user_email || attrs.customer_email || '';
  const name      = attrs.user_name  || attrs.first_name || 'Valued Customer';
  const amount    = (attrs.total || attrs.subtotal || 1000) / 100;

  if (!email) return res.status(400).json({ error: 'no email in payload' });

  // Generate 1-year license key
  const exp = new Date(); exp.setFullYear(exp.getFullYear() + 1);
  const expStr = exp.getFullYear() + String(exp.getMonth()+1).padStart(2,'0') + String(exp.getDate()).padStart(2,'0');
  const key = generateKey(expStr, db.settings.licenseSecret);
  const expiryTs = exp.getTime();

  // Store in DB
  db.licenses[key] = { key, expiryDate: expStr, expiryTs, createdAt: Date.now(),
    notes: `Auto: ${eventName} from ${email}`, source: 'lemonsqueezy' };
  db.settings.revenue = (db.settings.revenue || 0) + amount;
  log(db, 'webhook', 'license_generated', { key, email, amount, source: 'lemonsqueezy' });
  log(db, 'webhook', 'revenue_recorded',  { amount, note: `auto: ${email}` });
  writeDb(db);

  // Send the key by email
  try {
    if (db.settings.smtpUser && db.settings.smtpPass) {
      await sendKeyEmail(email, name, key, exp.toDateString(), db.settings);
      log(db, 'webhook', 'key_emailed', { email, key }); writeDb(db);
    } else {
      console.warn('[webhook] SMTP not configured — key generated but NOT emailed:', key, email);
    }
  } catch (e) {
    console.error('[webhook] Email failed:', e.message);
    log(db, 'webhook', 'email_failed', { email, error: e.message }); writeDb(db);
  }

  res.json({ ok: true, key, email });
});

// Generic payment webhook (any processor: Stripe, PayPal, etc.)
app.post('/api/webhook/payment', async (req, res) => {
  const db     = readDb();
  const { email, name, amount, secret: hookSecret } = req.body;

  // Simple shared-secret auth
  if (db.settings.lemonWebhookSecret && hookSecret !== db.settings.lemonWebhookSecret) {
    return res.status(401).json({ error: 'invalid secret' });
  }
  if (!email) return res.status(400).json({ error: 'email required' });

  const exp = new Date(); exp.setFullYear(exp.getFullYear() + 1);
  const expStr = exp.getFullYear() + String(exp.getMonth()+1).padStart(2,'0') + String(exp.getDate()).padStart(2,'0');
  const key    = generateKey(expStr, db.settings.licenseSecret);

  db.licenses[key] = { key, expiryDate: expStr, expiryTs: exp.getTime(), createdAt: Date.now(),
    notes: `Auto-pay ${amount ? '$'+amount : ''} from ${email}`, source: 'generic' };
  db.settings.revenue = (db.settings.revenue || 0) + (parseFloat(amount) || 10);
  log(db, 'webhook', 'license_generated', { key, email, amount, source: 'generic' });
  writeDb(db);

  try {
    if (db.settings.smtpUser && db.settings.smtpPass) {
      await sendKeyEmail(email, name || 'Subscriber', key, exp.toDateString(), db.settings);
    }
  } catch (e) { console.error('[webhook] Email failed:', e.message); }

  res.json({ ok: true, key });
});

// ── Serve the admin panel HTML ────────────────────────────────────────────────
app.get('/', (req, res) => res.sendFile(path.join(__dirname, 'panel.html')));
app.get('/panel', (req, res) => res.sendFile(path.join(__dirname, 'panel.html')));

// ── GitHub Releases (OTA Update) ──────────────────────────────────────────────
const upload = multer({ dest: path.join(__dirname, 'tmp_uploads') });

// List all releases from GitHub
app.get('/api/admin/releases', async (req, res) => {
  const db = readDb();
  const { githubToken } = db.settings;
  const repo = db.settings.githubReleasesRepo || 'CrestronDude/socatv-releases';
  try {
    const data = await ghApi(`https://api.github.com/repos/${repo}/releases`, 'GET', null, githubToken);
    res.json(data.map(r => ({
      id: r.id, tag: r.tag_name, name: r.name, body: r.body,
      createdAt: r.created_at, publishedAt: r.published_at,
      apkUrl: (r.assets||[]).find(a=>a.name.endsWith('.apk'))?.browser_download_url || '',
      downloadCount: (r.assets||[]).reduce((s,a)=>s+a.download_count,0)
    })));
  } catch(e) { res.status(500).json({ error: e.message }); }
});

// Publish a new release: accepts multipart APK upload
app.post('/api/admin/releases/publish', upload.single('apk'), async (req, res) => {
  const db = readDb();
  const { githubToken } = db.settings;
  const repo = db.settings.githubReleasesRepo || 'CrestronDude/socatv-releases';
  const { versionCode, versionName, releaseNotes, forceUpdate } = req.body;

  if (!req.file) return res.status(400).json({ error: 'No APK file uploaded' });
  if (!versionCode || !versionName) return res.status(400).json({ error: 'versionCode and versionName required' });
  if (!githubToken) return res.status(400).json({ error: 'GitHub token not set in Settings' });

  try {
    // 1. Create the GitHub release
    const tag = `v${versionCode}`;
    const release = await ghApi(
      `https://api.github.com/repos/${repo}/releases`, 'POST',
      { tag_name: tag, target_commitish: 'main', name: `SocaTV Nova ${versionName}`,
        body: releaseNotes || `SocaTV Nova ${versionName}`, draft: false, prerelease: false },
      githubToken
    );

    // 2. Upload APK asset
    const apkBuffer = fs.readFileSync(req.file.path);
    const uploadUrl = release.upload_url.replace('{?name,label}', `?name=SocaTvNova.apk&label=SocaTV+Nova+APK`);
    const asset = await ghUpload(uploadUrl, apkBuffer, githubToken);
    fs.unlinkSync(req.file.path);

    // 3. Auto-update local config and push to Gist
    db.config.latestVersionCode  = parseInt(versionCode);
    db.config.latestVersionName  = versionName;
    db.config.latestApkUrl       = asset.browser_download_url;
    db.config.forceUpdate        = forceUpdate === 'true' || forceUpdate === true;
    writeDb(db);

    // Push signed config to Gist automatically
    const { gistId, configSigningSecret } = db.settings;
    if (githubToken && gistId) {
      try {
        const configToSign = { ...db.config, checkinApiKey: db.settings.checkinApiKey };
        const sig = signConfig(configToSign, configSigningSecret || 'N0v@C0nfigSig#2024!K3y');
        const signedConfig = { ...configToSign, sig };
        await ghApi(`https://api.github.com/gists/${gistId}`, 'PATCH',
          { files: { 'nova_config.json': { content: JSON.stringify(signedConfig, null, 2) } } },
          githubToken
        );
      } catch(_) {}
    }

    log(db, 'release', 'published', { tag, versionName, url: asset.browser_download_url });
    writeDb(db);

    res.json({ ok: true, tag, apkUrl: asset.browser_download_url, releaseUrl: release.html_url });
  } catch(e) {
    if (req.file && fs.existsSync(req.file.path)) fs.unlinkSync(req.file.path);
    res.status(500).json({ error: e.message });
  }
});

// Helper: GitHub API call (JSON)
function ghApi(url, method, body, token) {
  return new Promise((resolve, reject) => {
    const u = new URL(url);
    const opts = {
      hostname: u.hostname, port: 443, path: u.pathname + u.search,
      method, headers: {
        'Authorization': `Bearer ${token}`,
        'User-Agent':    'SocaTVNova-Admin',
        'Accept':        'application/vnd.github.v3+json',
        'Content-Type':  'application/json'
      }
    };
    const payload = body ? JSON.stringify(body) : null;
    if (payload) opts.headers['Content-Length'] = Buffer.byteLength(payload);
    const req = https.request(opts, r => {
      let d = '';
      r.on('data', c => d += c);
      r.on('end', () => {
        try { const j = JSON.parse(d); r.statusCode >= 400 ? reject(new Error(j.message||d)) : resolve(j); }
        catch(e) { reject(e); }
      });
    });
    req.on('error', reject);
    if (payload) req.write(payload);
    req.end();
  });
}

// Helper: upload binary asset to GitHub
function ghUpload(uploadUrl, buffer, token) {
  return new Promise((resolve, reject) => {
    const u = new URL(uploadUrl);
    const opts = {
      hostname: u.hostname, port: 443, path: u.pathname + u.search,
      method: 'POST', headers: {
        'Authorization':  `Bearer ${token}`,
        'User-Agent':     'SocaTVNova-Admin',
        'Accept':         'application/vnd.github.v3+json',
        'Content-Type':   'application/vnd.android.package-archive',
        'Content-Length': buffer.length
      }
    };
    const req = https.request(opts, r => {
      let d = '';
      r.on('data', c => d += c);
      r.on('end', () => {
        try { const j = JSON.parse(d); r.statusCode >= 400 ? reject(new Error(j.message||d)) : resolve(j); }
        catch(e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.write(buffer);
    req.end();
  });
}

// ─────────────────────────────────────────────────────────────────────────────
app.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('  ╔══════════════════════════════════════════╗');
  console.log('  ║   SOCATV NOVA  —  ADMIN CONTROL PANEL   ║');
  console.log('  ╚══════════════════════════════════════════╝');
  console.log('');
  console.log(`  Local:   http://localhost:${PORT}`);
  console.log(`  Network: http://YOUR_IP:${PORT}`);
  console.log('');
  console.log('  Press Ctrl+C to stop.');
});
