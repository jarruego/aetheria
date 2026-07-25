# ============================================================
# Aetheria AI - Variables del modulo (Oracle Cloud Always Free)
# Se rellenan en terraform.tfvars (ver terraform.tfvars.example).
# terraform.tfvars esta en .gitignore: NUNCA se sube.
# ============================================================

# ---------------- Credenciales OCI (API key) ----------------

variable "tenancy_ocid" {
  description = "OCID del tenancy (consola: Perfil > Tenancy). Empieza por ocid1.tenancy..."
  type        = string
}

variable "user_ocid" {
  description = "OCID del usuario que posee la API key (consola: Perfil > My profile)."
  type        = string
}

variable "fingerprint" {
  description = "Huella de la API key (la muestra la consola al anadirla, formato aa:bb:cc:...)."
  type        = string
}

variable "private_key_path" {
  description = <<-EOT
    Ruta ABSOLUTA al fichero .pem con la clave privada de la API key.
    Se pasa como ruta (no contenido) para que la clave no acabe en el tfstate.
  EOT
  type        = string
}

variable "region" {
  description = "Region de OCI donde desplegar. Debe ser la HOME region del tenancy: el Always Free solo funciona ahi."
  type        = string
  default     = "eu-madrid-1"
}

variable "compartment_ocid" {
  description = "OCID del compartment donde crear los recursos. Vacio = usar el tenancy (compartment raiz), que es lo normal en una cuenta Always Free."
  type        = string
  default     = ""
}

# ---------------- Nomenclatura y red ----------------

variable "name_prefix" {
  description = "Prefijo para el nombre de todos los recursos creados."
  type        = string
  default     = "aetheria"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.name_prefix))
    error_message = "name_prefix debe ser minusculas/numeros/guiones y empezar por letra (max 21)."
  }
}

variable "vcn_cidr" {
  description = "Rango CIDR de la red virtual (privado, no se solapa con nada del exterior)."
  type        = string
  default     = "10.20.0.0/16"
}

variable "subnet_cidr" {
  description = "Rango CIDR de la subred publica donde vive la instancia."
  type        = string
  default     = "10.20.1.0/24"
}

# ---------------- Acceso ----------------

variable "ssh_public_key" {
  description = <<-EOT
    CONTENIDO de la clave publica SSH (una linea, empieza por 'ssh-ed25519' o
    'ssh-rsa'). No es la ruta al fichero: pega el contenido de ~/.ssh/id_ed25519.pub.
    Es la unica forma de entrar en la maquina: OCI no crea contrasena.
  EOT
  type        = string

  validation {
    condition     = can(regex("^(ssh-rsa|ssh-ed25519|ecdsa-sha2-) ", var.ssh_public_key))
    error_message = "ssh_public_key debe ser el CONTENIDO de una clave publica OpenSSH, no una ruta."
  }
}

variable "ssh_allowed_cidr" {
  description = <<-EOT
    Origen permitido para SSH (22/TCP).
    Por defecto 0.0.0.0/0 (todo internet) para que el primer despliegue no falle,
    pero DEBE restringirse a la IP publica del dueno en cuanto la maquina este viva:
    ssh_allowed_cidr = "88.1.2.3/32". Ver la seccion de seguridad del README.
  EOT
  type        = string
  default     = "0.0.0.0/0"
}

# ---------------- Instancia ----------------

variable "availability_domain_index" {
  description = <<-EOT
    Dominio de disponibilidad a usar, 1-indexado. Muchas regiones tienen uno solo.
    Si el apply falla con 'Out of host capacity', probar con 2 o 3 (si existen)
    suele ser mas rapido que reintentar en el mismo AD. Ver README.
  EOT
  type        = number
  default     = 1

  validation {
    condition     = var.availability_domain_index >= 1 && var.availability_domain_index <= 3
    error_message = "availability_domain_index debe estar entre 1 y 3."
  }
}

variable "instance_shape" {
  description = "Shape de computo. VM.Standard.A1.Flex es el ARM Ampere del Always Free."
  type        = string
  default     = "VM.Standard.A1.Flex"
}

variable "instance_ocpus" {
  description = <<-EOT
    OCPUs de la instancia.

    TECHO ALWAYS FREE VIGENTE: 2 OCPUs A1 EN TOTAL por tenancy.
    Oracle recorto el limite (antes eran 4 OCPU / 24 GB). Aviso literal en la
    consola: "Always Free Ampere A1 Compute limits have changed to 2 OCPUs and
    12 GB of memory".

    Subir de 2 saca la instancia del Always Free: en una cuenta Free Tier sin
    upgrade la peticion sera RECHAZADA por limite de servicio; en una cuenta
    Pay As You Go GENERA CARGOS. No lo subas sin querer hacerlo.
  EOT
  type        = number
  default     = 2

  validation {
    condition     = var.instance_ocpus >= 1 && var.instance_ocpus <= 4
    error_message = "Maximo tecnico 4; el Always Free actual solo cubre 2 (mas = rechazo o cargo)."
  }
}

variable "instance_memory_gb" {
  description = <<-EOT
    Memoria en GB.

    TECHO ALWAYS FREE VIGENTE: 12 GB de RAM A1 EN TOTAL por tenancy
    (antes 24 GB). Ver la nota de instance_ocpus.

    El reparto de esos 12 GB entre los 8 contenedores esta documentado en
    README.md, seccion "Dimensionamiento". Subir de 12 = fuera del free tier.
  EOT
  type        = number
  default     = 12

  validation {
    condition     = var.instance_memory_gb >= 6 && var.instance_memory_gb <= 24
    error_message = "Maximo tecnico 24; el Always Free actual solo cubre 12 (mas = rechazo o cargo)."
  }
}

variable "boot_volume_gb" {
  description = <<-EOT
    Tamano del disco de arranque en GB. El Always Free incluye 200 GB de Block
    Volume EN TOTAL; 100 GB deja margen para un segundo volumen o una segunda VM.
    Los mundos de Minecraft + imagenes Docker + Postgres caben de sobra en 100.
  EOT
  type        = number
  default     = 100

  validation {
    condition     = var.boot_volume_gb >= 50 && var.boot_volume_gb <= 200
    error_message = "boot_volume_gb debe estar entre 50 y 200 (limite del free tier)."
  }
}

variable "operating_system" {
  description = "Sistema operativo de la imagen a buscar en el catalogo de OCI."
  type        = string
  default     = "Canonical Ubuntu"
}

variable "operating_system_version" {
  description = "Version del SO. 24.04 = Noble Numbat LTS (soporte hasta 2029)."
  type        = string
  default     = "24.04"
}

# ---------------- Aplicacion (parametros del cloud-init) ----------------

variable "git_repo_url" {
  description = "Repositorio a clonar en la maquina. Debe ser publico o accesible sin credenciales."
  type        = string
  default     = "https://github.com/jarruego/aetheria.git"
}

variable "git_branch" {
  description = "Rama del repositorio a desplegar."
  type        = string
  default     = "main"
}

variable "app_dir" {
  description = "Directorio de la maquina donde se clona el repo y se ejecuta docker compose."
  type        = string
  default     = "/opt/aetheria"
}

variable "minecraft_java_port" {
  description = "Puerto TCP de Velocity (unico punto de entrada Java, ADR-0005)."
  type        = number
  default     = 25565
}

variable "bedrock_port" {
  description = "Puerto UDP de Geyser (Bedrock: movil, consola)."
  type        = number
  default     = 19132
}

variable "paper_memory" {
  description = <<-EOT
    Heap de la JVM para CADA servidor Paper (lobby y main).

    docker-compose.yml usa una sola variable PAPER_MEMORY para los dos, asi que
    este valor se aplica dos veces. Con el techo actual de 12 GB, 2G es el
    maximo prudente:

      2 x (2G heap + ~0.7G fuera del heap) = ~5.4 GB
      + Velocity ~0.8 + Postgres ~0.4 + 3 servicios Python ~0.5
      + SO y Docker ~1.2                  = ~8.3 GB de 12  (margen ~3.7 GB)

    El desglose completo esta en README.md, seccion "Dimensionamiento".
    NO subir a 3G: 2 x 3G se come el margen y el OOM killer del kernel mata
    contenedores al azar (normalmente el Paper mas grande, en plena partida).
  EOT
  type        = string
  default     = "2G"
}

# --- Reparto asimetrico (preparado, todavia inerte) ---
#
# El lobby es una sala de bienvenida con portales (mundo minimo, borde de 200
# bloques, sin mobs) y main es el mundo real. Darles la misma memoria es
# desperdiciarla. docker-compose.yml todavia usa la variable compartida
# PAPER_MEMORY, asi que estas dos se escriben en el .env de la maquina pero
# aun NO las consume nadie: el dia que el compose las use, el reparto se
# aplica solo sin volver a tocar la infraestructura.

variable "lobby_memory" {
  description = <<-EOT
    Heap para el servidor 'lobby' cuando docker-compose.yml lo parametrice por
    separado. 1G basta para un hub acotado a 200 bloques y sin mobs (~1.5 GB
    de RSS). Ver README.md > Dimensionamiento.
  EOT
  type        = string
  default     = "1G"
}

variable "main_memory" {
  description = <<-EOT
    Heap para el servidor 'main' cuando docker-compose.yml lo parametrice por
    separado. 3G (~3.9 GB de RSS) es lo adecuado con los mundos acotados que
    se han decidido: overworld 3000x3000, nether 1000x1000 y End desactivado.
    Sin End y con un nether pequeno no hay que sostener tres dimensiones
    grandes a la vez, que es lo que obligaba a reservar 4G.
  EOT
  type        = string
  default     = "3G"
}
