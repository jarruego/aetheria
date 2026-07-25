# Fase 4 — Oracle Cloud: estado y traspaso

> Documento de traspaso escrito el **2026-07-25** por la sesión que preparó la
> infraestructura cloud. Recoge **qué está hecho, qué falta y qué decisiones se
> tomaron**, para que otra sesión pueda continuar sin repetir el análisis.
>
> La guía de despliegue paso a paso vive en `infra/terraform/README.md` (699 líneas).
> Este fichero es el **estado**, no el manual.

---

## 1. Qué existe ya en Oracle Cloud

Aplicado con `terraform apply` el 2026-07-25. **5 de 7 recursos creados**:

| Recurso | Estado |
|---|---|
| `oci_core_vcn.this` (`10.20.0.0/16`) | ✅ creado |
| `oci_core_internet_gateway.this` | ✅ creado |
| `oci_core_security_list.public` | ✅ creado |
| `oci_core_route_table.public` | ✅ creado |
| `oci_core_subnet.public` | ✅ creado |
| `oci_core_instance.this` | ❌ **`Out of host capacity`** |
| `oci_core_public_ip.this` | ⏸ depende de la instancia |

**La instancia ARM no se pudo crear.** No es un error de configuración: el shape
`VM.Standard.A1.Flex` del Always Free está saturado y Oracle no tenía hueco en
`eu-madrid-1`. Es el comportamiento normal y esperado; se resuelve insistiendo.

El estado de Terraform (`infra/terraform/terraform.tfstate`) es válido y refleja los
5 recursos. Un nuevo `apply` **no los recrea**: solo intenta lo que falta.

### Cuenta y coste

Cuenta **Free Tier sin upgrade**. Eso significa que si se piden recursos fuera de
Always Free, la API los **rechaza** (`400-LimitExceeded`) en vez de facturar. Todo lo
creado hasta ahora es gratuito sin límite de tiempo.

⚠️ Oracle **recortó los límites Always Free de Ampere A1**: ya no son 4 OCPU / 24 GB,
sino **2 OCPU / 12 GB**. Los defaults del módulo ya están ajustados a ese techo.

---

## 2. Cómo reanudar el despliegue

Los reintentos se lanzaron desde una sesión de Claude Code y **mueren al cerrarla**.
Para reanudarlos, desde una terminal propia:

```bash
cd infra/terraform
./retry-apply.sh          # reintenta cada 5 min, hasta 100 veces
./retry-apply.sh 300 500  # intervalo (s) y nº máximo de intentos
```

O un solo intento manual:

```bash
terraform apply
```

### Distinguir los dos errores que pueden salir

| Error | Significado | Qué hacer |
|---|---|---|
| `500-InternalError, Out of host capacity` | Oracle sin hueco ARM ahora mismo | Insistir. Es normal. |
| `400-LimitExceeded` | Se pide más de lo que da el Always Free | Bajar `instance_ocpus` / `instance_memory_gb` |

### Trampa del entorno Windows

El antivirus deja **ficheros de bloqueo huérfanos de 0 bytes**
(`.terraform.tfstate.lock.info`) cuando un `apply` muere a mitad. El síntoma es:

```
Error acquiring the state lock ... write .terraform.tfstate.lock.info: Access is denied.
```

**No es corrupción del estado.** Se arregla borrando ese fichero y reintentando.
Conviene borrarlo antes de cada intento en cualquier bucle de reintentos.

### Si la capacidad no aparece nunca

1. **Bajar a 1 OCPU / 6 GB** — hay mucha más disponibilidad para peticiones pequeñas.
   `A1.Flex` es redimensionable en caliente, así que se puede subir a 2/12 después con
   un reinicio. Contrapartida: con 6 GB no cabe la topología completa.
2. **Cambiar `availability_domain_index`** — poco probable que ayude: `eu-madrid-1` es
   una región pequeña y casi con seguridad tiene un único dominio de disponibilidad.

---

## 3. Credenciales y ficheros locales (NO están en git)

| Qué | Dónde |
|---|---|
| Clave API de OCI | `C:\Users\jarru\.oci\oci_api_key.pem` |
| Par SSH de la instancia | `C:\Users\jarru\.ssh\aetheria_oci` (+ `.pub`) |
| Variables reales | `infra/terraform/terraform.tfvars` |
| Estado de Terraform | `infra/terraform/terraform.tfstate` |

`.gitignore` cubre `*.pem`, `*.tfvars`, `*.tfstate*`, `.terraform/` y (añadido en
`infra/terraform/.gitignore`) `tfplan`. **Ninguno de estos ficheros debe subirse.**

Región: `eu-madrid-1`. SSH restringido a la IP pública del dueño
(`46.136.182.135/32` el 2026-07-25). **Si esa IP cambia, el puerto 22 queda cerrado**
hasta actualizar `ssh_allowed_cidr` y reaplicar:

```bash
terraform apply -var="ssh_allowed_cidr=$(curl -s ifconfig.me)/32"
```

**No existe riesgo de quedarse fuera de forma permanente**: el acceso a la API de OCI
(Terraform, con la clave `.pem`) es independiente del SSH y funciona desde cualquier IP.
Como último recurso están también la **Cloud Shell** y la **consola serie** de Oracle,
que no pasan por la security list.

> ❌ **Un DNS dinámico (DuckDNS y similares) NO resuelve esto**: las security lists de
> OCI solo aceptan **CIDR**, nunca nombres de dominio. Un hostname dinámico sirve para
> la dirección que usan los **jugadores** (`algo.duckdns.org` en vez de la IP), no para
> la regla del puerto 22. Como la instancia lleva IP pública **reservada** (fija), de
> DuckDNS solo se aprovecha el nombre gratuito, no la parte dinámica. Ojo: DuckDNS no
> ofrece registros **SRV**, así que solo vale si se sirve en el puerto estándar 25565.

---

## 4. Decisión de arranque: **sin lobby**

Acordado con el dueño el 2026-07-25, motivado por el recorte a 2 OCPU / 12 GB.

**Se arranca solo con `main` (overworld + nether). Sin lobby y sin End.**

Razonamiento:

- El cuello de botella real son las **2 OCPU**, no la RAM.
- El lobby es un hub casi vacío: su coste de CPU es bajo, pero su suelo de JVM
  (~800 MB de RSS aunque el mundo esté vacío) no baja de ahí.
- El End es un mundo entero que se tickea aunque no haya nadie dentro.
- Reactivar el lobby después es descomentar un servicio de compose.

### Reparto de memoria resultante

| Componente | Heap | RSS aprox. |
|---|---|---|
| Main (overworld + nether) | 4 GB | ~5 GB |
| Velocity | 512 MB | ~0,8 GB |
| Postgres | — | ~0,3 GB |
| 3 servicios Python | — | ~0,6 GB |
| SO + Docker | — | ~1 GB |
| **Total** | | **~7,7 GB de 12** |

Colchón: ~4,3 GB.

> ⚠️ **No subir el heap de `main` a 8 GB.** Con 2 núcleos, un heap grande alarga las
> pausas de GC y produce tirones peores que los que evita. 4 GB es el punto dulce;
> 6 GB el techo razonable.

### Lo que se pierde sin lobby

**El fallback.** Con `try = ["main"]`, si `main` se cae o reinicia, Velocity no tiene a
dónde mandar a los jugadores y los expulsa con *"no se pudo conectar"*. En desarrollo,
donde se reinicia a menudo, esto se nota. Es el motivo principal para volver a añadir
el lobby más adelante.

También queda **sin probar el salto entre servidores** (pendiente de Fase 1).

**Velocity se mantiene** aunque solo haya un backend: es la frontera de seguridad
(ADR-0005), es quien autentica, y es lo que da Geyser/Floodgate. Además permite añadir
el lobby después sin que los jugadores cambien de dirección.

---

## 5. Cambios pendientes FUERA de `infra/`

> ✅ **RESUELTO (2026-07-25)** — aplicado en el commit "arranque sin lobby" (8eb2813):
> velocity `try=["main"]`, servicio `lobby` y volumen `lobby-data` comentados, `main`
> con `MAIN_MEMORY:-4G` + VIEW_DISTANCE 8 + SIMULATION_DISTANCE 5 + ALLOW_NETHER, End
> desactivado vía `minecraft/bukkit.yml.template` + `gen-mc-config`, Chunky vía
> `MODRINTH_PROJECTS=chunky`, y `MAIN_MEMORY`/`LOBBY_MEMORY` en `.env.example`.
> Verificado en local. El texto de abajo se conserva como referencia.

Estos cambios **no se aplicaron** para no colisionar con la sesión que trabajaba en
Fase 3 sobre los mismos ficheros. Están listos para aplicar.

### 5.1 `minecraft/proxy-velocity/velocity.toml`

```toml
[servers]
main = "main:25565"     # eliminar la línea  lobby = "lobby:25565"
try = ["main"]          # antes: ["lobby"]
```

### 5.2 `docker-compose.yml` — servicio `velocity`

```yaml
    depends_on:
      - main            # eliminar  - lobby
```

### 5.3 `docker-compose.yml` — servicio `lobby`

Comentar el servicio entero (y su volumen `lobby-data`). No borrarlo: se reactivará.

### 5.4 `docker-compose.yml` — servicio `main`

```yaml
      MEMORY: "${MAIN_MEMORY:-4G}"   # antes: "${PAPER_MEMORY:-2G}"
      VIEW_DISTANCE: "8"
      SIMULATION_DISTANCE: "5"
      ALLOW_NETHER: "true"
```

Si en algún momento se reactiva el lobby, separar la variable compartida:

```yaml
  lobby:
      MEMORY: "${LOBBY_MEMORY:-1G}"
      VIEW_DISTANCE: "4"
      SIMULATION_DISTANCE: "3"
      SPAWN_MONSTERS: "false"
      SPAWN_ANIMALS: "false"
      SPAWN_NPCS: "false"
      LEVEL_TYPE: "FLAT"        # opcional
```

El `cloud-init` **ya escribe `LOBBY_MEMORY=1G` y `MAIN_MEMORY=3G`** en el `.env` de la
máquina. Hoy están inertes porque el compose usa `PAPER_MEMORY`. Al aplicar lo de
arriba pasan a tener efecto. Ajustar `MAIN_MEMORY` a `4G` en
`infra/terraform/variables.tf` si se adopta el reparto sin lobby.

### 5.5 Desactivar el End

**No existe variable de entorno ni clave de `server.properties` para esto.** Es
`settings.allow-end: false` en **`bukkit.yml`**. Hace falta:

1. Crear `minecraft/bukkit.yml.template`
2. Añadir una línea en `scripts/gen-mc-config.sh` y `.ps1` que lo materialice
3. Montar el fichero en el servicio `main`

Se hace igual que ya se hace con `paper-global.yml`.

### 5.6 Plugin Chunky (pregeneración)

Añadir al servicio `main` en `docker-compose.yml`:

```yaml
      PLUGINS: >-
        https://cdn.modrinth.com/data/fALzjamp/versions/<ver>/Chunky-<ver>.jar
```

(Comprobar la URL vigente en Modrinth.) Sin Chunky no se puede pregenerar.

### 5.7 `.env.example`

Añadir `LOBBY_MEMORY` y `MAIN_MEMORY`.

---

## 6. ⚠️ Hueco detectado: el plugin Java NO se despliega

> ✅ **RESUELTO (2026-07-25)** — commit "compilar y desplegar el plugin via compose"
> (13a2630). Se eligió una **cuarta vía** mejor que las tres de abajo: un servicio
> one-shot `plugin-build` (imagen `gradle:8.10.2-jdk21`) compila el jar dentro de un
> contenedor y lo deja en `minecraft/.generated/main/plugins`, que `main` monta como
> `/data/plugins`. Así **el mismo `docker compose up` construye el plugin en local y en
> la VM**, sin JDK en el host ni release de GitHub (regla de oro 6). `main` recibe
> `INTERNAL_SERVICE_TOKEN` para que el plugin se autentique. Verificado en local: `main`
> carga Aetheria (y Chunky) automáticamente. El análisis original se conserva abajo.

**Verificado el 2026-07-25**: `minecraft/plugin-aetheria/` compila a
`build/libs/aetheria-plugin-0.1.0.jar`, pero:

- **`docker-compose.yml` no lo monta** en ningún servicio.
- **El `cloud-init` no lo compila** (no instala JDK ni ejecuta Gradle).

Consecuencia: **en la nube, `main` arrancaría sin el plugin de Aetheria**, es decir,
sin el puente IA↔mundo. Los servicios de backend funcionarían, pero `/aetheria ask|plan`
no existiría dentro del juego.

Hay que decidir e implementar una de estas vías:

1. **Compilar en la máquina**: añadir JDK 21 + `./gradlew build` al `cloud-init` y
   montar el `.jar` resultante. Cuesta CPU y tiempo en cada despliegue.
2. **Publicar el `.jar`** como release de GitHub y que el `cloud-init` lo descargue.
   Más rápido y reproducible; requiere un paso de publicación.
3. **Montar el `.jar` construido en local** vía `scp`. Simple, pero manual y frágil.

Recomendación: la **opción 2** encaja mejor con la regla de oro nº 6 (reproducibilidad).

---

## 7. Después de que la instancia arranque

Secuencia, ya documentada en detalle en `infra/terraform/README.md`:

1. Esperar 10–20 min al bootstrap:
   `ssh ubuntu@<IP> 'sudo tail -f /var/log/aetheria-bootstrap.log'` hasta
   `BOOTSTRAP COMPLETADO`.
2. Subir la API key del LLM (solo si se quiere IA real):
   `export ANTHROPIC_API_KEY=... && ./upload-secrets.sh <IP>`
3. **Pregenerar los mundos con Chunky ANTES de dar la IP a nadie.** Overworld
   3000×3000 tarda 1–3 h en 2 OCPU. Es lo que evita los tirones de generación en
   caliente, que es el mayor pico de CPU del sistema.
4. Bordes de mundo: overworld 3000×3000, nether 1000×1000.
5. Verificar que `ssh_allowed_cidr` sigue siendo la IP correcta.

---

## 8. Secretos en el despliegue cloud

- `VELOCITY_FORWARDING_SECRET`, `POSTGRES_PASSWORD` e `INTERNAL_SERVICE_TOKEN` se
  generan **en la propia máquina** con `openssl` durante el `cloud-init`. No viajan.
- La **API key del LLM** se sube por `scp` con `upload-secrets.sh`, **no** por metadata
  de instancia. Motivo: la metadata (`169.254.169.254`) es legible desde cualquier
  contenedor, y una variable Terraform `sensitive = true` se escribe **en claro en el
  `tfstate`**.

---

## 9. Riesgos conocidos

- **2 OCPU es el cuello de botella real.** Tope práctico estimado: 8–10 jugadores
  concurrentes. Un LLM local es inviable; la IA real va contra la API de Claude
  (la CPU no sufre, pero la latencia de red sí se notará en la conversación con NPC).
- **`Out of host capacity`** puede retrasar el despliegue horas o días.
- **Un solo host**: sin alta disponibilidad ni backups automáticos. El snapshot del
  boot volume queda pendiente. Dominio y TLS tampoco están cubiertos.
- **Acotar los mundos es semi-permanente**: ampliar el borde después deja costura
  visible entre lo pregenerado y lo nuevo.
- **`lifecycle.ignore_changes`** sobre `source_id` y `user_data` evita que Terraform
  quiera **recrear la VM** (y perder los mundos) al publicarse una imagen nueva o al
  editar el `cloud-init`. Contrapartida: reaplicar el `cloud-init` es manual
  (`sudo /usr/local/bin/aetheria-bootstrap.sh`), que además es el mecanismo de
  redespliegue tras un `git push`.
- **El `cloud-init` nunca se ha ejecutado de verdad.** Está validado como YAML y su
  bash pasa `bash -n`, pero no se ha probado en una máquina real porque no hubo
  capacidad. Si falla, depurar por SSH en `/var/log/aetheria-bootstrap.log`.

---

## 10. Cómo destruir todo

```bash
cd infra/terraform
terraform destroy
```

Elimina los 7 recursos. **Se pierden los mundos** si la instancia ya existía y no se
hizo backup del volumen.
