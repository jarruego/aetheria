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
3. **Firma de la textura**: si la skin NO está firmada, la firma se pasa como **`null`**, NUNCA
   como `""` (cadena vacía = firma inválida → el cliente muestra la skin por defecto). En modo
   offline una textura sin firmar (null) sí renderiza.

## Añadir skins de oficio
El dueño pasa una skin como `/give player_head[...]` (o URL de NameMC). Se saca el `value` base64
de `profile.properties[textures]` y se añade en `SkinCache.loadProfSkins()`:
`putProfSkin("<oficio_en_ingles>", "<value>", "");` (firma vacía = sin firmar → el listener la pasa
como null). El oficio del tabernero es `leatherworker` (TAVERN_KEEPER = Villager.Profession.LEATHERWORKER).

## Verificación
Solo se puede **ver en el juego** (los disfraces son visuales, cliente). Hay que **reconectar** para
que los NPC ya cargados se re-spawneen con la skin nueva. Server-side se comprueba: plugin carga,
"skins humanas activas", "SkinCache: N skins", 0 excepciones.
