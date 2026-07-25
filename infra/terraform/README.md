# Despliegue en Oracle Cloud Always Free (Fase 4)

Infraestructura como código para levantar **toda** la topología de Aetheria (Velocity +
Lobby + Main + api-gateway + ai-orchestrator + world-state + Postgres) en una única
máquina ARM del *free tier* de Oracle Cloud, **sin instalar nada a mano** (regla de oro 6).

Está escrito para alguien que **nunca ha usado Oracle Cloud ni Terraform**. Sigue los
pasos en orden.

---

## 0. Qué se va a crear

| Recurso | Para qué | ¿Always Free? |
|---|---|---|
| VCN + subred pública + Internet Gateway + route table | Red virtual | Sí (gratis siempre) |
| Security list | Cortafuegos: solo 22, 25565/TCP y 19132/UDP | Sí |
| Instancia `VM.Standard.A1.Flex` — **2 OCPU ARM / 12 GB RAM** | La máquina | Sí (es el máximo del free tier **actual**) |
| Boot volume de 100 GB | Disco | Sí (el free tier da 200 GB en total) |
| IP pública **reservada** | Que la IP no cambie al reiniciar | Sí |

Todo lo demás (Docker, el repo, la configuración de Minecraft, los secretos) lo hace
sola la máquina en su primer arranque vía `cloud-init.yaml`.

**Puertos abiertos a internet: solo tres.** Los del backend (`8080`, `8090`, `8070`) y la
base de datos (`5432`) **no se exponen jamás**; se llega a ellos por túnel SSH.

> ### ⚠️ Los límites Always Free cambiaron: ahora son **2 OCPU y 12 GB**
>
> Oracle recortó la cuota de Ampere A1. Aviso literal en la consola:
> *"Always Free Ampere A1 Compute limits have changed to 2 OCPUs and 12 GB of memory,
> review your current usage and update or re-provision instances to stay within the new
> limits."*
>
> Mucha documentación y muchos tutoriales por internet siguen diciendo **4 OCPU / 24 GB**:
> están desactualizados. Este módulo usa **2 / 12** como valores por defecto. Pedir más
> en una cuenta Free Tier no cuesta dinero, pero **falla** (ver sección 10).

---

## 1. Requisitos previos en tu ordenador

1. **Cuenta de Oracle Cloud** (gratuita, pide tarjeta solo para verificar identidad —
   ver la sección de costes más abajo). Apunta la **home region** que elijas al
   registrarte: el *Always Free* solo funciona ahí.
2. **Terraform** instalado: <https://developer.hashicorp.com/terraform/install>
   Comprueba con `terraform version`.
3. **Una clave SSH.** Si no tienes:
   ```bash
   ssh-keygen -t ed25519 -C "aetheria"
   ```
   Genera `~/.ssh/id_ed25519` (privada, no la compartas) y `~/.ssh/id_ed25519.pub`
   (pública, la que se pega en `terraform.tfvars`).

---

## 2. Sacar la API key de Oracle (lo más lioso, se hace una vez)

Terraform necesita hablar con Oracle en tu nombre. Eso son 5 datos.

1. Entra en <https://cloud.oracle.com> con tu cuenta.
2. Arriba a la derecha, icono de **perfil** → **My profile**.
3. En el menú de la izquierda, abajo del todo: **API keys** → botón **Add API key**.
4. Deja marcado **Generate API key pair** y pulsa **Download private key**.
   Guarda el `.pem` en un sitio estable, por ejemplo `C:\Users\tu-usuario\.oci\oci_api_key.pem`.
   *Esa clave privada no se puede volver a descargar.*
5. Pulsa **Add**. Oracle muestra entonces un **fichero de configuración** parecido a esto
   — **cópialo, tiene 4 de los 5 datos**:
   ```ini
   [DEFAULT]
   user=ocid1.user.oc1..aaaa...
   fingerprint=aa:bb:cc:dd:...
   tenancy=ocid1.tenancy.oc1..aaaa...
   region=eu-madrid-1
   key_file=<path to your private keyfile>
   ```
6. Correspondencia con las variables de este módulo:

   | Del fichero de Oracle | Variable de Terraform |
   |---|---|
   | `tenancy` | `tenancy_ocid` |
   | `user` | `user_ocid` |
   | `fingerprint` | `fingerprint` |
   | `region` | `region` |
   | (la ruta donde guardaste el `.pem`) | `private_key_path` |

> En Windows, escribe la ruta del `.pem` con **barras normales**:
> `"C:/Users/tu-usuario/.oci/oci_api_key.pem"`.

---

## 3. Rellenar `terraform.tfvars`

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars
```

Abre `terraform.tfvars` y rellena:

- Los 5 valores del paso 2.
- `ssh_public_key`: **el contenido** de `~/.ssh/id_ed25519.pub` (una sola línea, empieza
  por `ssh-ed25519`). No la ruta.
- `ssh_allowed_cidr`: déjalo en `0.0.0.0/0` para el primer despliegue y **restringelo
  después** (ver seguridad).

`terraform.tfvars` está en `.gitignore`: nunca se sube al repositorio.

---

## 4. Desplegar

```bash
cd infra/terraform

terraform init      # descarga el provider de Oracle
terraform plan      # muestra qué va a crear, sin crear nada
terraform apply     # lo crea (pide escribir 'yes')
```

Al terminar imprime algo así:

```
public_ip              = "140.238.x.y"
minecraft_java_address = "140.238.x.y:25565"
ssh_command            = "ssh ubuntu@140.238.x.y"
bootstrap_log_command  = "ssh ubuntu@140.238.x.y 'sudo tail -f /var/log/aetheria-bootstrap.log'"
```

**El `apply` termina antes que la máquina.** Terraform solo espera a que exista la VM;
el `cloud-init` (instalar Docker, clonar, compilar imágenes, descargar Paper/Velocity/
Geyser) tarda **entre 5 y 15 minutos** más. Sigue el progreso con:

```bash
ssh ubuntu@<IP> 'sudo tail -f /var/log/aetheria-bootstrap.log'
```

Cuando veas `BOOTSTRAP COMPLETADO`, conecta el cliente de Minecraft a `<IP>:25565`.

---

## 5. ⚠️ "Out of host capacity" — te va a pasar, y no es culpa tuya

El error más frecuente de este despliegue:

```
Error: 500-InternalError
Out of host capacity.
```

**Qué significa:** las máquinas ARM (Ampere A1) del *Always Free* son gratis para
siempre, así que están constantemente agotadas. Oracle no reserva capacidad para cuentas
gratuitas: te da un hueco **si en ese instante lo hay** en ese dominio de disponibilidad.
No es un fallo de configuración, ni de tu cuenta, ni de este módulo: **es una cola**.

**Qué hacer:**

1. **Insistir.** Hay un script para eso:
   ```bash
   ./retry-apply.sh          # reintenta cada 5 minutos, hasta 100 veces
   ./retry-apply.sh 180 300  # cada 3 minutos, 300 intentos
   ```
   Terraform es idempotente: la red ya creada no se toca, solo reintenta la instancia.
   Suele caer en unas horas; a veces en un par de días. Deja el bucle corriendo.
2. **Probar otro dominio de disponibilidad**, si tu región tiene más de uno:
   `availability_domain_index = 2` (o `3`) en `terraform.tfvars`.
3. **Pedir menos**: `instance_ocpus = 2` / `instance_memory_gb = 12` tiene más hueco
   libre. Sigue siendo suficiente para arrancar (bájale `paper_memory` a `2G`), y luego
   se puede ampliar la shape en caliente desde la consola cuando haya capacidad.
4. **Upgrade a Pay As You Go** (opcional, ver costes): las cuentas de pago tienen
   prioridad de capacidad y **los recursos Always Free siguen siendo gratis**.

---

## 6. Secretos: cómo se gestionan

El `.env` **no está en git** y contiene tres tipos de secretos. Se tratan distinto:

### 6.1 Secretos de infraestructura → se generan **en la máquina**

`VELOCITY_FORWARDING_SECRET`, `POSTGRES_PASSWORD` e `INTERNAL_SERVICE_TOKEN` los genera
el propio `cloud-init` con `openssl rand`, dentro de la VM, y escribe el `.env` con
permisos `600`.

**No viajan por la red, no pasan por Terraform, no están en tu portátil y no aparecen en
el `terraform.tfstate`.** Es la opción más segura y no cuesta nada: son valores que no
tienen por qué existir en ningún otro sitio.

### 6.2 API key del LLM → se sube **después**, por `scp`

Esa sí viene de fuera. Tras el `apply`:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./upload-secrets.sh <IP_PUBLICA>
```

El script la deja en el `.env` de la máquina (permisos 600) y recarga `ai-orchestrator`.
Mientras no la subas, **el servidor de Minecraft funciona igual**: solo la IA queda sin
proveedor.

**Por qué `scp` y no una variable `sensitive` de Terraform inyectada por metadata:**

- `sensitive = true` **solo oculta el valor en la salida de consola**. El valor se
  escribe **en claro** en `terraform.tfstate`. Ese fichero acabaría en el disco del
  dueño (y algún día en un backend remoto) con una clave de pago dentro.
- Además, la metadata de una instancia OCI es legible desde dentro de la máquina por
  **cualquier** proceso o contenedor (`http://169.254.169.254/opc/v2/instance/`). Una
  API key facturable no merece estar ahí.
- Coste de la alternativa: un comando manual una vez. Barato.

### 6.3 Qué queda en el `terraform.tfstate` (aviso)

`terraform.tfstate` **no es un fichero público**. Con esta configuración contiene:

- OCIDs, IPs, y **tu clave pública SSH** (no es secreta).
- **No** contiene la clave privada de la API de OCI (se pasa como *ruta*, no como valor).
- **No** contiene la API key del LLM ni ningún secreto del `.env`.

Aun así: `.gitignore` ya excluye `*.tfstate` y `*.tfvars`. **No los subas nunca.** Si
algún día se añade una variable con un secreto real, seguirá siendo cierto que
*cualquier* valor que pase por Terraform queda en claro en el estado.

---

## 7. ARM64: ¿construyen las imágenes?

La máquina es **ARM (aarch64)**, tu portátil es x86. Las imágenes se compilan **en la
propia máquina** durante el `cloud-init` (`docker compose up -d --build`), así que se
construyen nativamente para `linux/arm64`. Revisión de las bases usadas:

| Imagen base | Dónde | Multi-arch (arm64) |
|---|---|---|
| `python:3.12-slim` | `services/api-gateway`, `services/ai-orchestrator`, `services/world-state` | Sí (oficial, `linux/arm64` publicado) |
| `postgres:16` | `postgres`, `migrate` | Sí (oficial) |
| `itzg/minecraft-server` | `lobby`, `main` | Sí (`amd64` + `arm64`) |
| `itzg/mc-proxy` | `velocity` | Sí (`amd64` + `arm64`) |

**Conclusión: no hay que hacer nada.** Ningún `Dockerfile` fija `--platform`, ninguna
base es solo-x86, y las dependencias Python del proyecto tienen *wheels* para
`manylinux_aarch64` (o compilan). Los plugins de Geyser/Floodgate son `.jar`: la
arquitectura les da igual.

Lo único a recordar **si algún día se publican imágenes en un registro** en lugar de
construirlas en la máquina: hay que construirlas para ARM explícitamente, porque un
`docker build` en el portátil produciría binarios x86 inservibles aquí.

```bash
docker buildx build --platform linux/arm64 -t <registro>/aetheria-api-gateway:x \
  ./services/api-gateway --push
```

> Nota: compilar los tres servicios Python en 4 OCPU ARM tarda unos minutos la primera
> vez. Es normal que el `bootstrap.log` parezca parado durante ese rato.

---

## 8. Dimensionamiento: cómo caben 8 contenedores en 12 GB

La topología de `docker-compose.yml` son 8 servicios (uno, `migrate`, es efímero). Los dos
servidores Paper **no son iguales**: el `lobby` es una sala de bienvenida con portales —un
mundo casi sin contenido— y `main` es el mundo persistente que va a crecer. Repartir la
memoria a partes iguales entre ellos sería tirarla.

### Reparto objetivo

| Componente | Heap (`-Xmx`) | RSS real estimado | Nota |
|---|---|---|---|
| Ubuntu + Docker + `containerd` | — | **~1,1 GB** | Incluye page cache del SO |
| `postgres:16` | — | **~0,3 GB** | Config por defecto, `shared_buffers` 128 MB |
| `api-gateway` + `ai-orchestrator` + `world-state` | — | **~0,6 GB** | ~200 MB cada uno (Python + FastAPI) |
| `velocity` (JVM + Geyser + Floodgate) | 512 MB | **~0,8 GB** | No simula mundo, solo mueve paquetes |
| `lobby` (Paper, **mundo mínimo**) | **1 GB** | **~1,5 GB** | Borde de 200 bloques, sin mobs |
| `main` (Paper, **mundos acotados**) | **3 GB** | **~3,9 GB** | Overworld 3000², Nether 1000², sin End |
| **Total** | | **~8,2 GB** | **Margen libre: ~3,8 GB de 12** |

**Caben los dos servidores Paper con holgura.** `main` no necesita 4 GB precisamente
porque los mundos están acotados: sin End y con un Nether pequeño no hay que sostener
tres dimensiones grandes a la vez, que es lo que obligaba a reservar más heap. Los 3,8 GB
de colchón no sobran: absorben los picos de GC, el `docker build` y el page cache que
Linux usa para los ficheros de región del mundo.

**Por qué el RSS es mayor que el heap:** una JVM no consume solo su `-Xmx`. Súmale
*metaspace*, buffers directos de red (Netty, y Paper usa muchos), pilas de hilos, código
JIT compilado y las estructuras internas del GC. La regla práctica es **heap + 0,5 a 1 GB**
por servidor Paper — menos en el lobby precisamente porque un mundo minúsculo apenas usa
buffers ni chunks residentes.

### Estado actual: el valor por defecto sigue siendo `2G` para ambos, y es correcto

`docker-compose.yml` usa **una sola** variable `PAPER_MEMORY` para `lobby` y `main`, así
que hoy **no se puede** dar 1G a uno y 4G a otro. Por eso el módulo despliega con
`paper_memory = "2G"` (2 × ~2,7 GB = 5,4 GB; total ~8,3 GB de 12: seguro).

> ⚠️ **No pongas `paper_memory = "4G"` mientras la variable sea compartida**: serían
> **dos** servidores de 4 GB (~10 GB de RSS) y la máquina entra en *OOM killer*, que mata
> procesos sin avisar — normalmente el más gordo, es decir, el servidor con jugadores
> dentro. Con la variable compartida, el techo es `2G`.

El `cloud-init` **ya escribe** `LOBBY_MEMORY=1G` y `MAIN_MEMORY=3G` en el `.env` de la
máquina (variables `lobby_memory` / `main_memory` de este módulo). Hoy están **inertes**:
en cuanto `docker-compose.yml` las use, el reparto 1G/3G se aplica solo, sin volver a
tocar la infraestructura. El cambio concreto pendiente está reportado fuera de este
módulo.

### El reparto solo se cumple si el lobby va en modo mínimo

Un `lobby` con la configuración por defecto de Paper (view-distance 10, mobs activos,
mundo infinito) **no cabe en 1 GB**: generaría chunks sin parar y arrastraría también las
2 OCPU. Ajustes necesarios en el lobby:

| Ajuste | Valor | Por qué |
|---|---|---|
| `view-distance` | `4` | Cada nivel de distancia crece **al cuadrado** en chunks cargados. De 10 a 4 es ~6× menos memoria y CPU. Un hub se ve entero con 4. |
| `simulation-distance` | `3` | Limita el radio donde se hace *tick* de entidades y bloques. Es lo que más CPU ahorra, y las 2 OCPU son el recurso escaso. |
| `spawn-monsters` | `false` | Sin mobs hostiles: menos entidades, menos IA, menos *pathfinding*, cero riesgo de acumulación en un hub donde nadie los mata. |
| `spawn-animals` | `false` | Igual, y evita que la granja de pasivos crezca sola con el tiempo. |
| *World border* | **~200 bloques** | Un hub no necesita más. Impide que un jugador se aleje y dispare generación de terreno nuevo (lo más caro que hace Minecraft). |

Los tres primeros son claves de `server.properties`; la imagen `itzg/minecraft-server` los
acepta como variables de entorno (`VIEW_DISTANCE`, `SIMULATION_DISTANCE`, `SPAWN_MONSTERS`,
`SPAWN_ANIMALS`). El *world border* se fija una vez desde la consola del lobby y queda
guardado en el mundo:

```bash
docker compose exec lobby rcon-cli worldborder set 200
```

Estos ajustes viven en `docker-compose.yml` (fuera de este módulo): están reportados como
cambio pendiente. **Hasta que se apliquen, no bajes `lobby` a 1 GB.**

---

## 9. Rendimiento: qué esperar de 2 OCPU

**El cuello de botella de esta máquina es la CPU, no la RAM.** La memoria cabe con
margen (sección 8); las **2 OCPU** son las que van justas, y son la mitad de las que daba
antes el free tier. Qué significa en la práctica:

**Lo que va bien:**

- **Uso normal con pocos jugadores (1–5).** Minecraft es sobre todo *single-threaded* en
  el hilo principal del *tick*; con poca gente, cada Paper no satura ni una OCPU.
- **Los servicios Python** (`api-gateway`, `ai-orchestrator`, `world-state`) están casi
  todo el tiempo dormidos esperando I/O. No compiten por CPU.
- **Postgres** con este volumen de datos es ruido de fondo.
- **Velocity** apenas usa CPU: mueve paquetes.

**Lo que va a doler:**

- **La compilación inicial.** `docker compose up --build` de los tres servicios Python
  más la descarga de Paper/Velocity/Geyser tarda **10–20 minutos** en 2 OCPU. Es normal
  que el log parezca colgado; no lo está.
- **La generación de chunks.** Explorar mundo nuevo (o un `/tp` a 10.000 bloques) es lo
  más caro que hace Minecraft, y con 2 OCPU se traduce en tirones de TPS inmediatos.
  **Esto se resuelve del todo acotando y pregenerando los mundos** (ver justo abajo): si
  no queda terreno nuevo que generar, el pico desaparece.
- **Arranque simultáneo.** Cuando ambos Paper arrancan a la vez, las dos OCPU se saturan
  durante unos minutos.
- **Más de ~8–10 jugadores concurrentes** en `main` mientras `lobby` sigue encendido: ahí
  es donde esta máquina se queda corta de verdad.
- **Ojo con la IA (Fase 3):** el LLM corre **fuera** (API de Anthropic), así que no
  consume CPU aquí. Un modelo local en esta máquina **no es viable**; ni de lejos.

### La solución: mundos acotados + pregeneración

La generación de chunks es, con diferencia, **el mayor pico de CPU de todo el sistema**.
Un solo jugador volando en *elytra* por terreno nuevo puede tumbar el TPS de un servidor
con 2 OCPU. La estrategia acordada elimina ese pico **del todo**: se acotan los mundos y
se generan **una sola vez, sin nadie dentro**.

| Mundo | Borde (diámetro) | Notas |
|---|---|---|
| **Lobby** | **200 bloques** | Superflat o void, sin mobs |
| **Main — Overworld** | **3000 × 3000** | ~9 km²; sobra para las primeras fases |
| **Main — Nether** | **1000 × 1000** | O desactivado directamente |
| **Main — End** | **desactivado** | No aporta nada en fases tempranas y cuesta memoria |

Con esos bordes, todo el terreno alcanzable se puede pregenerar y, a partir de ahí, jugar
**no genera terreno nuevo nunca**: solo se leen del disco ficheros de región ya escritos.
Es la diferencia entre un servidor que da tirones y uno que va fino en hardware modesto.

### Pregeneración: cuándo y cómo

**Cuándo: justo después del primer `apply`, y antes de dar la IP a nadie.** Ese es el
único momento en que la máquina tiene las 2 OCPU enteras para ella.

El plugin habitual es **Chunky** (gratuito, en `main`). Procedimiento:

```bash
ssh ubuntu@<IP>
cd /opt/aetheria

# 1. Fijar los bordes (el número es el DIÁMETRO en bloques, centrado en el spawn).
#    'worldborder' aplica al mundo desde el que se ejecuta: la consola está en el
#    overworld, así que el Nether necesita 'execute in'.
docker compose exec main rcon-cli worldborder set 3000
docker compose exec main rcon-cli "execute in minecraft:the_nether run worldborder set 1000"

# 2. Pregenerar el overworld: radio = mitad del borde
docker compose exec main rcon-cli chunky world minecraft:overworld
docker compose exec main rcon-cli chunky center 0 0
docker compose exec main rcon-cli chunky radius 1500
docker compose exec main rcon-cli chunky start

# Progreso (Chunky lo va cantando en el log)
docker compose logs -f main

# 3. Cuando termine, el nether (radio 500)
docker compose exec main rcon-cli chunky world minecraft:the_nether
docker compose exec main rcon-cli chunky radius 500
docker compose exec main rcon-cli chunky start
```

**Cuánto tarda en 2 OCPU ARM:**

| Mundo | Chunks aprox. | Tiempo estimado |
|---|---|---|
| Overworld 3000×3000 (radio 1500) | ~35.000 | **1–3 horas** |
| Nether 1000×1000 (radio 500) | ~4.000 | **15–40 min** |
| Lobby 200×200 | ~170 | segundos |

Son órdenes de magnitud, no promesas: dependen de la versión, del bioma y de si Chunky
va a tope o limitado. Déjalo corriendo y vuelve luego; **no lo interrumpas a medias** con
jugadores conectados. El disco resultante ronda unos pocos GB, muy holgado en los 100 GB
del boot volume.

**Espacio en disco tras pregenerar:** un overworld de 9 km² ocupa del orden de 1–3 GB en
ficheros de región. Cabe de sobra.

### ¿Debería lanzarlo el `cloud-init` automáticamente?

**Decisión: no, y a propósito.** Razones:

- En el primer arranque las 2 OCPU ya están **saturadas** compilando las tres imágenes
  Python y descargando Paper/Velocity/Geyser. Añadir encima una pregeneración de 35.000
  chunks alargaría el bootstrap a varias horas con la máquina al 100%, y el *healthcheck*
  de los servicios podría empezar a fallar por *timeout*.
- La pregeneración depende de decisiones de **juego** (semilla, centro, radio, si hay
  Nether) que se toman una vez y a conciencia. Automatizarlas en la infraestructura las
  congelaría con un valor por defecto que casi seguro no es el bueno.
- Es un paso **de una sola vez** en la vida del servidor. No merece complejidad
  permanente en el `cloud-init`.
- Si se hiciera automático, tendría que ser **desatendido y reanudable**: Chunky lo es
  (guarda progreso), pero un reinicio en mitad del bootstrap dejaría un estado a medias
  difícil de diagnosticar por SSH.

Se hace a mano, una vez, con la receta de arriba.

### Aviso: acotar el mundo es una decisión de juego semi-permanente

Ampliar el borde después es **técnicamente trivial** (`worldborder set 6000` y otra pasada
de Chunky). Lo que no se puede deshacer es la **costura**: el terreno generado más tarde
puede haberlo sido con otra versión de Minecraft o con otros ajustes de generación, y en
la frontera se ven cortes bruscos —acantilados rectos, biomas cortados a cuchillo, cuevas
que terminan en pared—. Cuanto más tiempo pase entre las dos pasadas, más se nota.

Además, si para entonces hay construcciones de jugadores cerca del borde antiguo, la
costura queda en zona habitada y a la vista.

Traducción práctica: **elige el borde pensando en dónde quieres estar dentro de un año**,
no solo ahora. 3000×3000 son 9 km²: para una civilización de decenas de jugadores es
mucho más de lo que parece.

### Vigilar

```bash
ssh ubuntu@<IP>
docker stats --no-stream     # CPU y RAM por contenedor
free -h                      # memoria libre; si 'available' baja de ~1 GB, hay problema
docker compose logs main | grep -i "can't keep up"   # síntoma clásico de falta de CPU
```

La consola de OCI también muestra CPU y memoria (el plugin de monitorización viene
activado en `compute.tf`), gratis.

---

## 10. Seguridad: lo que hay que hacer en cuanto entre

1. **Restringir SSH.** Averigua tu IP pública (`curl ifconfig.me`) y pon en
   `terraform.tfvars`:
   ```hcl
   ssh_allowed_cidr = "88.12.34.56/32"
   ```
   y `terraform apply`. Si tu IP es dinámica, tendrás que repetirlo de vez en cuando;
   es un precio pequeño por quitar el 22 de internet entero.
2. **No abrir más puertos.** El backend se depura por túnel:
   ```bash
   ssh -N -L 8080:localhost:8080 -L 8090:localhost:8090 -L 8070:localhost:8070 ubuntu@<IP>
   # y luego, en el portátil: http://localhost:8080/health
   ```
3. **Velocity queda expuesto a internet.** Está configurado con `online-mode = true`
   (autentica contra Mojang) y `player-info-forwarding-mode = "modern"` con secreto
   compartido; los Paper no son alcanzables desde fuera (ADR-0005). Esa combinación es
   la correcta: **no la cambies**.

### El detalle que rompe el 90% de los despliegues en Oracle

Las imágenes Ubuntu de Oracle traen un `iptables` que **rechaza todo el tráfico
entrante** salvo el 22, *independientemente* de lo que diga la security list de la VCN.
Resultado típico: consola de Oracle con el puerto 25565 abierto y el servidor "sin
responder".

`cloud-init.yaml` **ya lo resuelve**: inserta reglas `ACCEPT` para `25565/tcp` y
`19132/udp` en la posición 1 de la cadena `INPUT` y las persiste con
`netfilter-persistent save`. Si alguna vez añades un puerto en la security list y no
funciona, es esto. Comprobación:

```bash
sudo iptables -L INPUT -n --line-numbers
```

---

## 11. Operación diaria

```bash
ssh ubuntu@<IP>
cd /opt/aetheria

docker compose ps                 # estado
docker compose logs -f velocity   # logs de un servicio
sudo systemctl status aetheria    # unidad que lo levanta tras un reboot

# Desplegar cambios del repo (git pull + rebuild + configs):
sudo /usr/local/bin/aetheria-bootstrap.sh
```

Tras un `reboot` la topología vuelve sola: por la unidad `aetheria.service` **y** por el
`restart: unless-stopped` de cada contenedor.

---

## 12. Costes: qué es gratis, y qué pasa si te pasas

Los recursos de esta configuración están **dentro** del *Always Free* de Oracle (límites
**vigentes**, ya recortados):

- **2 OCPU ARM Ampere + 12 GB de RAM** en total por tenancy (aquí: una VM que los usa
  todos). *Antes eran 4 / 24: la documentación que diga eso está desactualizada.*
- **200 GB** de Block Volume en total (aquí: 100 GB de boot volume).
- **2 IPs públicas reservadas** (aquí: 1).
- **10 TB/mes** de tráfico de salida.
- VCN, subredes, gateways, security lists, monitorización básica: gratis.

**Cómo te saldrías de ahí:**

- Crear una **segunda** VM ARM, o subir OCPU/RAM por encima de **2 / 12 en total**.
- Superar los 200 GB de disco. Ojo: destruir una instancia dejando el disco vivo consume
  cuota indefinidamente (este módulo pone `preserve_boot_volume = false`, así que no pasa).
- Usar un shape que no sea `VM.Standard.A1.Flex` ni `VM.Standard.E2.1.Micro`.
- Añadir Load Balancer de pago, backups automáticos de bloque, Object Storage extra, etc.

### La red de seguridad: esta cuenta es Free Tier **sin upgrade**

Mientras la cuenta no se pase a *Pay As You Go*, **Oracle no puede cobrarte nada**. No
hay tarjeta que facturar: si pides algo fuera del Always Free, la API **rechaza la
petición** en vez de crear el recurso y pasarte la factura.

Eso es bueno, pero significa que **un exceso de cuota se manifiesta como un error de
Terraform**, no como un cargo. Hay que saber distinguirlo:

```
Error: 400-LimitExceeded
You have reached your service limit ... for shape VM.Standard.A1.Flex
```

- **`LimitExceeded` / `service limit`** → has pedido **más de lo que el free tier cubre**
  (p. ej. `instance_ocpus = 4`). **Es culpa de la configuración**: baja los valores a
  2/12. También sale si ya tienes **otra** instancia A1 viva consumiendo la cuota: la
  cuota es **por tenancy**, no por instancia. Revisa *Compute > Instances* y borra lo
  viejo.
- **`Out of host capacity`** → distinto problema: la cuota te da derecho, pero Oracle no
  tiene hardware libre ahora mismo. **No es culpa tuya**, hay que reintentar (sección 5).

Si algún día haces *upgrade* a **Pay As You Go**: los recursos Always Free siguen siendo
gratis, **pero desaparece la red de seguridad** y cualquier cosa fuera de los límites se
factura de verdad. En ese caso, activa un **budget con alerta a 1 €** en *Billing & Cost
Management* antes de tocar nada.

> Aviso adicional: Oracle **recupera** instancias de cuentas Always Free que llevan
> ~7 días con la CPU casi al 0%. Un servidor de Minecraft con jugadores no corre ese
> riesgo; uno completamente vacío durante semanas, sí. (Las cuentas de pago no sufren
> esta política.)

---

## 13. Destruir todo

```bash
cd infra/terraform
terraform destroy     # pide escribir 'yes'
```

Borra la instancia, el boot volume, la IP reservada y la red. **Es irreversible: se
pierden los mundos de Minecraft y la base de datos.** Si quieres conservarlos, antes:

```bash
ssh ubuntu@<IP> 'cd /opt/aetheria && docker compose down'
ssh ubuntu@<IP> 'sudo tar czf /tmp/aetheria-volumes.tgz /var/lib/docker/volumes'
scp ubuntu@<IP>:/tmp/aetheria-volumes.tgz .
```

---

## 14. Ficheros de este directorio

| Fichero | Qué es |
|---|---|
| `versions.tf` | Versiones fijadas de Terraform y del provider OCI |
| `providers.tf` | Autenticación contra Oracle Cloud |
| `variables.tf` | Todas las variables, con descripción y validaciones |
| `network.tf` | VCN, subred, gateway, route table y **security list** (el cortafuegos) |
| `compute.tf` | Instancia ARM, búsqueda de la imagen Ubuntu e IP reservada |
| `outputs.tf` | IP, comando SSH, dirección para el cliente, túnel |
| `cloud-init.yaml` | Lo que la máquina se hace a sí misma al arrancar |
| `terraform.tfvars.example` | Plantilla de configuración (copia a `terraform.tfvars`) |
| `upload-secrets.sh` | Sube la API key del LLM tras el apply |
| `retry-apply.sh` | Bucle de reintento para "Out of host capacity" |

Decisión de arquitectura asociada: `docs/adr/0008-cloud-oracle-always-free.md`.

---

## 15. Apéndice: cambios pendientes **fuera** de `infra/`

Este módulo solo toca `infra/`. Lo siguiente hace falta para que el dimensionamiento de
la sección 8 se cumpla de verdad, y vive en ficheros que este módulo no modifica. Está
listado con fichero, clave y valor exactos para que se pueda aplicar sin investigar.

### 15.1 `docker-compose.yml` — memorias separadas para `lobby` y `main`

Hoy ambos usan `MEMORY: "${PAPER_MEMORY:-2G}"`. Sustituir por:

```yaml
  lobby:
    environment:
      MEMORY: "${LOBBY_MEMORY:-1G}"
  main:
    environment:
      MEMORY: "${MAIN_MEMORY:-3G}"
```

`cloud-init` ya escribe `LOBBY_MEMORY=1G` y `MAIN_MEMORY=3G` en el `.env` de la máquina,
así que el cambio surte efecto sin tocar Terraform. En `.env.example` habría que añadir
esas dos claves (y `PAPER_MEMORY` puede quedarse como compatibilidad o retirarse).

### 15.2 `docker-compose.yml` — servicio `lobby` en modo mundo mínimo

Añadir al bloque `environment:` del servicio `lobby`:

| Clave | Valor |
|---|---|
| `VIEW_DISTANCE` | `"4"` |
| `SIMULATION_DISTANCE` | `"3"` |
| `SPAWN_MONSTERS` | `"false"` |
| `SPAWN_ANIMALS` | `"false"` |
| `SPAWN_NPCS` | `"false"` |
| `LEVEL_TYPE` | `"FLAT"` *(opcional: superflat; si el hub se construye a mano, mejor esto que un mundo normal)* |

Son claves de `server.properties` que la imagen `itzg/minecraft-server` mapea desde
variables de entorno.

### 15.3 `docker-compose.yml` — servicio `main`, dimensiones acotadas

| Clave | Valor | Efecto |
|---|---|---|
| `ALLOW_NETHER` | `"true"` | Nether activo pero acotado a 1000×1000 por *world border* |
| `VIEW_DISTANCE` | `"8"` | Por debajo del 10 por defecto; ahorra CPU sin que se note apenas |
| `SIMULATION_DISTANCE` | `"5"` | Lo que más CPU ahorra en un servidor con 2 OCPU |

Para **desactivar el End** no hay variable de entorno ni clave en `server.properties`: es
`settings.allow-end: false` en **`bukkit.yml`**. Requiere una plantilla nueva
(`minecraft/bukkit.yml.template`) montada como los `paper-global.yml` que ya genera
`scripts/gen-mc-config.sh`, o un `bukkit.yml` montado directamente en `/data/bukkit.yml`.

### 15.4 Comandos de mundo (no son ficheros: se ejecutan una vez)

```bash
docker compose exec lobby rcon-cli worldborder set 200
docker compose exec main  rcon-cli worldborder set 3000
docker compose exec main  rcon-cli "execute in minecraft:the_nether run worldborder set 1000"
```

Quedan guardados en el mundo (`level.dat`): se hacen una sola vez.

### 15.5 Plugin Chunky en `main` (para la pregeneración)

`docker-compose.yml`, servicio `main`, añadir al `environment:` la descarga del plugin
(el mismo mecanismo que ya usa `velocity` con `PLUGINS`):

```yaml
      PLUGINS: >-
        https://cdn.modrinth.com/data/fALzjamp/versions/<VERSION>/Chunky-<VERSION>.jar
```

(o dejar el `.jar` en un volumen de plugins). Sin él no hay `chunky` en la consola y la
pregeneración de la sección 9 no se puede ejecutar.
