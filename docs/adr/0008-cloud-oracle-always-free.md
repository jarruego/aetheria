# ADR-0008: Infraestructura cloud en Oracle Cloud Always Free (una VM ARM, Terraform, cloud-init)

- **Estado:** Aceptada
- **Fecha:** 2026-07-25

## Contexto

La Fase 4 pide llevar la topología completa (Velocity + Lobby + Main + api-gateway +
ai-orchestrator + world-state + Postgres) a un servidor accesible desde internet, sin
coste recurrente y sin pasos manuales (regla de oro 6: IaC + reproducibilidad).

Restricciones reales del entorno elegido:

- La cuenta es **Oracle Cloud Free Tier sin upgrade**, región `eu-madrid-1`.
- Oracle **recortó** la cuota Always Free de Ampere A1: ya no son 4 OCPU / 24 GB sino
  **2 OCPU y 12 GB** por tenancy (aviso explícito en la consola del dueño). Casi toda la
  documentación pública sigue citando los límites antiguos.
- El shape ARM gratuito está crónicamente agotado: `Out of host capacity` es la respuesta
  habitual al primer intento de aprovisionamiento.
- Las imágenes Ubuntu de OCI traen reglas `iptables` que rechazan el tráfico entrante
  aunque la *security list* de la VCN esté abierta.

## Decisión

### 1. Una única VM ARM, con toda la topología en contenedores

`VM.Standard.A1.Flex` con **2 OCPU y 12 GB** (el techo Always Free vigente), Ubuntu 24.04
ARM64, boot volume de 100 GB (de los 200 GB gratuitos).

Esto **no rompe** la regla de oro 5 (microservicios desde el día 1): cada servicio sigue
siendo un contenedor con su propio ciclo de vida y su propia URL. Repartirlos en varias
máquinas es cambiar variables de entorno, no rediseñar. Simplemente, hoy el free tier da
una sola máquina y no hay motivo para complicarla.

La imagen del SO **no se hardcodea**: se resuelve con el data source `oci_core_images`
filtrando por shape, porque los OCID de imagen cambian por región y por revisión.

### 2. Terraform define la infraestructura; `cloud-init` define la máquina

Terraform crea red y cómputo. Todo lo que ocurre *dentro* de la máquina (Docker, clonado
del repo, generación de configuración de Minecraft, arranque) lo hace `cloud-init.yaml`
mediante un script idempotente que también sirve para redesplegar (`git pull` + rebuild).

El arranque tras un reinicio está garantizado por partida doble: una unidad `systemd`
(`aetheria.service`) y el `restart: unless-stopped` que ya tienen los contenedores.

Consecuencia deliberada: **la máquina se despliega desde el repositorio público**, no
desde artefactos subidos. La fuente de verdad sigue siendo git.

### 3. Superficie de ataque mínima: tres puertos

La *security list* abre solo `22/TCP` (restringible a una IP), `25565/TCP` (Velocity) y
`19132/UDP` (Geyser). El backend (`8080`, `8090`, `8070`) y Postgres (`5432`) **no se
exponen nunca**; se acceden por túnel SSH. Coherente con ADR-0005: Velocity es el único
punto de entrada, con `online-mode = true` y *modern forwarding*.

Además, el `cloud-init` abre esos dos puertos también en el `iptables` **del sistema**,
porque la security list por sí sola no basta en las imágenes de Oracle.

### 4. Secretos: generados en destino; solo la API key viaja

- `VELOCITY_FORWARDING_SECRET`, `POSTGRES_PASSWORD` e `INTERNAL_SERVICE_TOKEN` se generan
  **en la propia máquina** con `openssl rand` durante el `cloud-init`. No viajan por la
  red, no pasan por Terraform y no existen en el portátil del dueño.
- La **API key del LLM** es la única que viene de fuera. Se sube **después** del apply por
  `scp` (`infra/terraform/upload-secrets.sh`), no por metadata de la instancia.

Se descarta inyectarla como variable Terraform `sensitive`: `sensitive` solo oculta el
valor en la consola, pero lo escribe **en claro en el `terraform.tfstate`**; y la metadata
de instancia es legible desde dentro por cualquier proceso o contenedor
(`169.254.169.254`). El coste de la alternativa es un comando manual, una vez.

### 5. Mundos acotados y pregenerados

Con 2 OCPU, la generación de chunks en caliente es el mayor pico de CPU del sistema. Se
acotan los mundos (Lobby 200 bloques; Overworld 3000×3000; Nether 1000×1000; End
desactivado en fases tempranas) y se **pregeneran una sola vez, sin jugadores**, antes de
abrir. A partir de ahí, jugar solo lee ficheros de región ya escritos.

La pregeneración **no** se automatiza en el `cloud-init`: en el primer arranque la CPU ya
está saturada compilando imágenes, y la semilla/centro/radio son decisiones de juego, no
de infraestructura.

Reparto de memoria resultante sobre 12 GB: lobby 1 GB de heap (~1,5 GB reales), main 3 GB
(~3,9 GB), Velocity ~0,8 GB, Postgres ~0,3 GB, tres servicios Python ~0,6 GB, SO y Docker
~1,1 GB. Total **~8,2 GB**, con ~3,8 GB de colchón.

## Alternativas descartadas

- **Kubernetes gestionado (OKE) o varias VMs**: fuera del free tier y complejidad que hoy
  no compra nada.
- **Un proveedor de hosting de Minecraft**: barato, pero no permite desplegar los
  servicios backend propios ni cumplir la regla de IaC.
- **Imagen de SO fijada por OCID**: se rompe al cambiar de región y envejece mal.
- **Secretos en un gestor externo (Vault, OCI Vault)**: correcto a futuro, desproporcionado
  para una máquina y cuatro secretos, y OCI Vault no es Always Free.

## Consecuencias

- (+) Coste **cero** verificable, y en Free Tier sin upgrade Oracle no puede facturar: si
  se pide algo fuera de cuota, la API lo rechaza en vez de cobrarlo.
- (+) El entorno se recrea entero desde cero con `terraform apply`; nada instalado a mano.
- (+) IP pública **reservada**: no cambia al reiniciar, los jugadores la tienen apuntada.
- (+) Los secretos de infraestructura no existen fuera de la máquina.
- (-) **2 OCPU son el cuello de botella real.** La RAM sobra; la CPU no. Más de ~8-10
  jugadores concurrentes se notará, y el primer `docker build` tarda 10-20 minutos.
- (-) Un solo host = un solo punto de fallo, sin alta disponibilidad ni backups
  automáticos (pendiente: snapshot programado del boot volume).
- (-) Aprovisionar puede requerir **horas de reintentos** por `Out of host capacity`; se
  mitiga con `infra/terraform/retry-apply.sh`.
- (-) Acotar los mundos es una decisión de juego semi-permanente: ampliar el borde luego
  deja una costura visible entre lo pregenerado y lo nuevo.
- (-) Queda pendiente lo que la Fase 4 también menciona y aquí no se cubre: dominio, TLS
  para la API, monitorización más allá del agente de OCI y backups.
