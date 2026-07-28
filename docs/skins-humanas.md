# Skins humanas de los NPC

Los NPC (colonos, tabernero, mercader, Aeon) se ven como **jugadores con skin**, no como
aldeanos. El aldeano sigue existiendo por dentro (IA, conversación, rutinas, protección): solo
cambia lo que **ve el cliente**.

## Por qué packetevents y NO LibsDisguises
El servidor corre en **Java 25** (lo exige FAWE 2.15.3). **ProtocolLib peta en Java 25**
(ByteBuddy `Unsafe/ClassInjector`), y **LibsDisguises depende de ProtocolLib** → descartados.
`packetevents` (ya instalado, 2.13.0) **sí funciona en Java 25**. NO están en Modrinth/Spiget:
`packetevents` lo trae el server; si algún día hace falta, los .jar se meten a mano en
`minecraft/.generated/main/plugins/`.

## Cómo funciona (3 clases)
- **`DisguiseModule`**: registro `uuid → {sexo, nombre, oficio}`. `humanize(entity, gender, name,
  profKey)` marca a un NPC. Dependencia BLANDA: sin packetevents, no se registra el listener y los
  NPC se quedan de aldeanos.
- **`SkinCache`**: skins. `loadProfSkins()` = skins **por oficio** (clave = `Villager.Profession`
  en minúsculas, p. ej. `farmer`, `toolsmith`, `leatherworker`=tabernero, `trader`=mercader,
  `concierge`=Aeon); tienen prioridad. `loadAsync()` = set por **sexo** (fallback, baja de Mojang).
- **`HumanSkinListener`** (packetevents): intercepta `SPAWN_ENTITY` de un NPC nuestro → manda
  `PLAYER_INFO_UPDATE` (ADD_PLAYER con la skin) y reescribe el tipo a `PLAYER`; filtra su
  `ENTITY_METADATA` (índices > 7, que a un jugador no le valen; conserva la base para el nametag).

## Gotchas (aprendidos a base de probar)
1. **El nombre encima** de un jugador es el del **perfil** (≤16 chars, sin espacios), NO el
   custom-name de entidad. Por eso el nombre real va como perfil con espacios→`_`
   ("Francisco Ramos" → "Francisco_Ramos").
2. **La skin solo carga si el jugador está `listed=true`** en el tab (muchos clientes ignoran la
   skin de los no-listados). En el `PlayerInfo` va `listed=true`.
3. **La textura DEBE ir FIRMADA** (esto costó): los clientes modernos exigen `value`+`signature`
   válidos para renderizar la skin de OTRO jugador; con firma `null`/`""` muestran la skin por
   defecto (da igual el modo offline). Se firma con **MineSkin** (sin cuenta): `POST
   https://api.mineskin.org/generate/url` con `{"url":"<url .png de la textura>","visibility":1}`
   → devuelve `data.texture.value` + `data.texture.signature` (rate limit ~6 s entre peticiones).
   La firma valida el `profileId` embebido en el `value`, NO el UUID del NPC, así que la misma
   textura firmada vale para cualquier UUID. El listener pasa la firma tal cual si no está vacía.

## Añadir skins de oficio
El dueño pasa una skin como `/give player_head[...]` (o URL de NameMC): se saca el `value` base64
de `profile.properties[textures]`, se decodifica para leer la **url .png** de la textura y se
**firma con MineSkin** (ver gotcha #3). En `SkinCache.loadProfSkins()`:
`putProfSkin("<oficio_en_ingles>", "<value_firmado>", "<signature>");`. El oficio del tabernero es
`leatherworker` (TAVERN_KEEPER = Villager.Profession.LEATHERWORKER).

## Verificación
Solo se puede **ver en el juego** (los disfraces son visuales, cliente). Hay que **reconectar** para
que los NPC ya cargados se re-spawneen con la skin nueva. Server-side se comprueba: plugin carga,
"skins humanas activas", "SkinCache: N skins", 0 excepciones.
