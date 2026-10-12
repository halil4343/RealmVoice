'use strict';

const dgram = require('node:dgram');

const PORT = Number(process.env.VOICE_PORT || 24454);
const TOKEN = process.env.VOICE_TOKEN || 'CHANGE_ME';
const ROOM = process.env.VOICE_ROOM || 'realm';
const MAX_DISTANCE = 150;
const CLIENT_TIMEOUT_MS = 6000;
const PLAYER_LIST_INTERVAL_MS = 1000;
const STATUS_INTERVAL_MS = 5000;
const MAX_PACKET = 1500;

const socket = dgram.createSocket('udp4');
const clients = new Map();
const endpointToUuid = new Map();

function endpointKey(rinfo) {
  return `${rinfo.address}:${rinfo.port}`;
}

function validUuid(value) {
  return typeof value === 'string'
    && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function cleanString(value, max, fallback = '') {
  if (typeof value !== 'string') return fallback;
  return value.replace(/[\u0000-\u001F\u007F]/g, '').slice(0, max);
}

function finiteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value);
}

function send(rinfo, buffer) {
  socket.send(buffer, rinfo.port, rinfo.address);
}

function sendWelcome(rinfo, ok) {
  send(rinfo, Buffer.from([1, 100, ok ? 1 : 0]));
}

function updateEndpoint(client, rinfo) {
  const key = endpointKey(rinfo);
  if (client.endpoint && client.endpoint !== key) endpointToUuid.delete(client.endpoint);
  client.endpoint = key;
  client.address = rinfo.address;
  client.port = rinfo.port;
  endpointToUuid.set(key, client.uuid);
}

function active(client) {
  return Date.now() - client.lastSeen <= CLIENT_TIMEOUT_MS;
}

function roomMembers(room) {
  return [...clients.values()].filter(c => c.room === room && active(c));
}

function handleHello(buf, rinfo) {
  try {
    const msg = JSON.parse(buf.subarray(2).toString('utf8'));
    const uuid = msg.uuid;
    const room = cleanString(msg.room, 64, ROOM);
    const tokenOk = msg.token === TOKEN;

    if (!tokenOk || !validUuid(uuid) || !room) {
      console.log(`[RealmVoice] Rejected HELLO from ${endpointKey(rinfo)} (bad token/uuid/room)`);
      sendWelcome(rinfo, false);
      return;
    }

    let client = clients.get(uuid);
    const firstJoin = !client;
    if (!client) {
      client = {
        uuid,
        room: room || ROOM,
        name: 'Player',
        dim: '',
        x: 0,
        y: 0,
        z: 0,
        yaw: 0,
        lastSeen: Date.now(),
        endpoint: '',
        address: '',
        port: 0
      };
      clients.set(uuid, client);
    }

    client.room = room || ROOM;
    client.name = cleanString(msg.name, 32, 'Player');
    client.lastSeen = Date.now();
    updateEndpoint(client, rinfo);

    sendWelcome(rinfo, true);
    sendPlayerList(client);

    if (firstJoin) {
      console.log(`[RealmVoice] JOIN  ${client.name} ${client.uuid} from ${client.address}:${client.port}`);
    } else {
      console.log(`[RealmVoice] HELLO ${client.name} ${client.uuid}`);
    }
    console.log(`[RealmVoice] Active clients: ${clients.size}`);
  } catch (e) {
    console.log(`[RealmVoice] Invalid HELLO from ${endpointKey(rinfo)}`);
    sendWelcome(rinfo, false);
  }
}

function handlePosition(buf, rinfo) {
  const uuid = endpointToUuid.get(endpointKey(rinfo));
  if (!uuid) return;

  try {
    const msg = JSON.parse(buf.subarray(2).toString('utf8'));
    const client = clients.get(uuid);
    if (!client || msg.uuid !== uuid) return;
    if (msg.room !== client.room || typeof msg.dim !== 'string') return;
    if (![msg.x, msg.y, msg.z, msg.yaw].every(finiteNumber)) return;

    client.name = cleanString(msg.name, 32, client.name);
    client.dim = cleanString(msg.dim, 128);
    client.x = msg.x;
    client.y = msg.y;
    client.z = msg.z;
    client.yaw = msg.yaw;
    client.lastSeen = Date.now();
  } catch {
  }
}

function distance(a, b) {
  const dx = a.x - b.x;
  const dy = a.y - b.y;
  const dz = a.z - b.z;
  return Math.sqrt(dx * dx + dy * dy + dz * dz);
}

function gainForDistance(d) {
  if (d >= MAX_DISTANCE) return 0;
  return Math.round((1 - d / MAX_DISTANCE) * 255);
}

function gainForPair(sender, recipient) {
  if (!sender.dim || sender.dim !== recipient.dim) return 0;
  return gainForDistance(distance(sender, recipient));
}

function panForPair(sender, recipient) {
  const dx = sender.x - recipient.x;
  const dz = sender.z - recipient.z;
  const horizontal = Math.hypot(dx, dz);
  if (horizontal < 0.0001) return 0;

  const nx = dx / horizontal;
  const nz = dz / horizontal;
  const yaw = recipient.yaw * Math.PI / 180;
  const rightX = Math.cos(yaw);
  const rightZ = -Math.sin(yaw);
  return Math.max(-1, Math.min(1, nx * rightX + nz * rightZ));
}

function handleAudio(buf, rinfo) {
  const senderUuid = endpointToUuid.get(endpointKey(rinfo));
  if (!senderUuid) return;

  // version(1), type(1), UUID ASCII(36), sequence(2), PCM(640)
  if (buf.length !== 2 + 36 + 2 + 640) return;

  const sender = clients.get(senderUuid);
  if (!sender) return;
  sender.lastSeen = Date.now();

  const senderId = Buffer.from(senderUuid, 'ascii');
  if (!buf.subarray(2, 38).equals(senderId)) return;

  const sequence = buf.readUInt16BE(38);
  const pcm = buf.subarray(40);

  for (const recipient of roomMembers(sender.room)) {
    if (recipient.uuid === senderUuid) continue;

    const gain = gainForPair(sender, recipient);
    if (gain <= 0) continue;

    const pan = Math.round(panForPair(sender, recipient) * 127);
    const out = Buffer.alloc(42 + pcm.length);
    out[0] = 1;
    out[1] = 3;
    senderId.copy(out, 2);
    out.writeUInt16BE(sequence, 38);
    out[40] = gain;
    out.writeInt8(pan, 41);
    pcm.copy(out, 42);

    socket.send(out, recipient.port, recipient.address);
  }
}

function uuidBytes(uuid) {
  return Buffer.from(uuid.replaceAll('-', ''), 'hex');
}

function buildPlayerList(client) {
  const members = roomMembers(client.room).slice(0, 64);
  const header = Buffer.alloc(4);
  header[0] = 1;
  header[1] = 101;
  header.writeUInt16BE(members.length, 2);
  const parts = [header];

  for (const p of members) {
    const name = Buffer.from(cleanString(p.name, 32, 'Player'), 'utf8');
    const row = Buffer.alloc(17 + name.length);
    uuidBytes(p.uuid).copy(row, 0);
    row[16] = name.length;
    name.copy(row, 17);
    parts.push(row);
  }
  return Buffer.concat(parts);
}

function sendPlayerList(client) {
  if (!client.address || !client.port) return;
  socket.send(buildPlayerList(client), client.port, client.address);
}

socket.on('message', (buf, rinfo) => {
  if (buf.length < 2 || buf.length > MAX_PACKET || buf[0] !== 1) return;
  switch (buf[1]) {
    case 1: handleHello(buf, rinfo); break;
    case 2: handlePosition(buf, rinfo); break;
    case 3: handleAudio(buf, rinfo); break;
    default: break;
  }
});

socket.on('error', err => console.error('[RealmVoice] UDP error:', err));

socket.bind(PORT, '0.0.0.0', () => {
  console.log('[RealmVoice] ================================');
  console.log(`[RealmVoice] UDP listening on 0.0.0.0:${PORT}`);
  console.log(`[RealmVoice] Voice room: ${ROOM}`);
  console.log(`[RealmVoice] Max distance: ${MAX_DISTANCE} blocks`);
  console.log('[RealmVoice] Spatial attenuation + directional pan: ON');
  console.log('[RealmVoice] ================================');
});

setInterval(() => {
  const now = Date.now();
  for (const [uuid, client] of clients) {
    if (now - client.lastSeen > CLIENT_TIMEOUT_MS) {
      clients.delete(uuid);
      if (client.endpoint) endpointToUuid.delete(client.endpoint);
      console.log(`[RealmVoice] LEAVE ${client.name} ${client.uuid}`);
    }
  }

  for (const client of clients.values()) {
    if (active(client)) sendPlayerList(client);
  }
}, PLAYER_LIST_INTERVAL_MS);

setInterval(() => {
  const activeCount = [...clients.values()].filter(active).length;
  console.log(`[RealmVoice] STATUS active_clients=${activeCount}`);
}, STATUS_INTERVAL_MS);
