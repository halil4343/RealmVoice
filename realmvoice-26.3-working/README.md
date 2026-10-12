RealmVoice 26.3

Client: replace the existing client source with the files in client/src.
Client config is generated at .minecraft/config/realmvoice.properties.
Set:
  server=YOUR_ORACLE_PUBLIC_IP
  port=24454
  room=realm
  token=THE_SAME_SECRET_AS_THE_SERVER

Server: run server/server.js with VOICE_PORT, VOICE_TOKEN and VOICE_ROOM in .env.
The server uses a 150 block max spatial distance and no group system.
V toggles microphone mute. X opens the control screen.
