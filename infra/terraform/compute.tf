# ============================================================
# Aetheria AI - Computo (instancia ARM Ampere + IP publica reservada)
#
# Una sola VM con TODA la topologia de docker-compose.yml. Sigue siendo
# microservicios (regla de oro 5): cada servicio es un contenedor con su
# propio ciclo de vida, y mover uno a otra maquina = cambiar una URL.
# ============================================================

# ---------------- Dominios de disponibilidad ----------------

data "oci_identity_availability_domains" "this" {
  compartment_id = var.tenancy_ocid
}

# ---------------- Imagen: Ubuntu 24.04 ARM64 ----------------
#
# NUNCA hardcodear un OCID de imagen: cambian por region y Canonical publica
# una revision nueva cada pocas semanas. Filtrando por `shape` el catalogo
# devuelve solo imagenes compatibles con A1.Flex, es decir aarch64.
data "oci_core_images" "ubuntu" {
  compartment_id           = local.compartment_id
  operating_system         = var.operating_system
  operating_system_version = var.operating_system_version
  shape                    = var.instance_shape
  sort_by                  = "TIMECREATED"
  sort_order               = "DESC"
}

locals {
  availability_domain = data.oci_identity_availability_domains.this.availability_domains[
    var.availability_domain_index - 1
  ].name

  # Descarta las variantes "Minimal": traen un userland recortado que da
  # sorpresas con cloud-init y con los repos de Docker.
  ubuntu_images = [
    for img in data.oci_core_images.ubuntu.images : img
    if length(regexall("(?i)minimal", img.display_name)) == 0
  ]

  # La mas reciente (la data source ya viene ordenada por fecha descendente).
  image_id = local.ubuntu_images[0].id
}

# ---------------- Instancia ----------------

resource "oci_core_instance" "this" {
  availability_domain = local.availability_domain
  compartment_id      = local.compartment_id
  display_name        = "${var.name_prefix}-host"
  shape               = var.instance_shape

  # A1.Flex es "flexible": OCPUs y RAM se piden por separado.
  #
  # TECHO ALWAYS FREE VIGENTE: 2 OCPU + 12 GB EN TOTAL por tenancy.
  # Oracle recorto el limite (antes 4 OCPU / 24 GB); la consola avisa con
  # "Always Free Ampere A1 Compute limits have changed to 2 OCPUs and 12 GB".
  # Pedir mas en una cuenta Free Tier sin upgrade = la API lo RECHAZA por
  # limite de servicio. En una cuenta Pay As You Go = se factura.
  # El reparto de esos 12 GB entre los contenedores esta en README.md.
  shape_config {
    ocpus         = var.instance_ocpus
    memory_in_gbs = var.instance_memory_gb
  }

  source_details {
    source_type             = "image"
    source_id               = local.image_id
    boot_volume_size_in_gbs = var.boot_volume_gb
  }

  create_vnic_details {
    subnet_id      = oci_core_subnet.public.id
    display_name   = "${var.name_prefix}-vnic"
    hostname_label = var.name_prefix

    # FALSE a proposito: la IP publica se asigna aparte como RESERVADA
    # (ver oci_core_public_ip). Si aqui pidieramos una efimera, OCI no
    # dejaria asociar despues la reservada al mismo VNIC.
    assign_public_ip = false
  }

  metadata = {
    # Clave SSH del dueno. OCI la instala en ~ubuntu/.ssh/authorized_keys.
    ssh_authorized_keys = var.ssh_public_key

    # cloud-init: instala Docker, abre iptables, clona el repo, genera .env
    # con secretos LOCALES y levanta docker compose. Ver cloud-init.yaml.
    user_data = base64encode(templatefile("${path.module}/cloud-init.yaml", {
      app_dir      = var.app_dir
      git_repo_url = var.git_repo_url
      git_branch   = var.git_branch
      java_port    = var.minecraft_java_port
      bedrock_port = var.bedrock_port
      paper_memory = var.paper_memory
      lobby_memory = var.lobby_memory
      main_memory  = var.main_memory
    }))
  }

  # El agente de OCI: dejamos el plugin de monitorizacion (gratis, util para
  # ver CPU/RAM en la consola) y desactivamos el de gestion (no lo usamos).
  agent_config {
    are_all_plugins_disabled = false
    is_management_disabled   = true
    is_monitoring_disabled   = false
  }

  # Al destruir la instancia se borra tambien el disco: sin esto quedan
  # boot volumes huerfanos consumiendo la cuota de 200 GB del free tier.
  preserve_boot_volume = false

  lifecycle {
    ignore_changes = [
      # Sin esto, cada vez que Canonical publica una imagen nueva Terraform
      # querria RECREAR la maquina (y perder los mundos). Para actualizar el
      # SO se usa `apt upgrade` por SSH, no un reemplazo de instancia.
      source_details[0].source_id,
      # cloud-init solo corre en el primer arranque: cambiarlo despues no
      # debe destruir la maquina. Para reaplicarlo, ejecutar a mano
      # /usr/local/bin/aetheria-bootstrap.sh por SSH.
      metadata["user_data"],
    ]

    # SEGURO CONTRA EL ERROR MAS CARO DE ESTE PROYECTO.
    #
    # Conseguir un hueco ARM en el Always Free cuesta horas o dias de
    # reintentos ("Out of host capacity"). Si un `apply` futuro decidiera
    # REEMPLAZAR la instancia (cambio de subred, de dominio de disponibilidad,
    # reduccion del boot volume...), Terraform la destruiria primero y luego
    # intentaria crear la nueva: si en ese momento no hay capacidad, el
    # servidor desaparece y puede tardar dias en volver. Ademas se pierden
    # los mundos, porque preserve_boot_volume = false.
    #
    # Con esto, cualquier plan que implique destruir la instancia FALLA en
    # seco en vez de ejecutarse.
    #
    # Contrapartida: `terraform destroy` tambien queda bloqueado. Para
    # desmontar el entorno a proposito hay que comentar esta linea primero.
    prevent_destroy = true
  }

  timeouts {
    # El aprovisionamiento de A1 puede tardar cuando la region va justa.
    create = "30m"
  }
}

# ---------------- IP publica reservada ----------------
#
# Una IP efimera cambia si la instancia se para y arranca. Los jugadores
# tienen la IP escrita en su cliente: tiene que ser estable.
# Para asociarla hace falta el OCID de la IP PRIVADA del VNIC primario.

data "oci_core_vnic_attachments" "primary" {
  compartment_id = local.compartment_id
  instance_id    = oci_core_instance.this.id
}

data "oci_core_private_ips" "primary" {
  vnic_id = data.oci_core_vnic_attachments.primary.vnic_attachments[0].vnic_id
}

resource "oci_core_public_ip" "this" {
  compartment_id = local.compartment_id
  display_name   = "${var.name_prefix}-ip"

  # RESERVED = sobrevive a paradas, reinicios y a recrear la instancia.
  lifetime      = "RESERVED"
  private_ip_id = data.oci_core_private_ips.primary.private_ips[0].id
}
