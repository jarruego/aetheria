# ============================================================
# Aetheria AI - Red (VCN, subred publica, gateway, firewall)
#
# Topologia minima a proposito: una VCN, una subred publica, un internet
# gateway. La instancia esta directamente en la subred publica porque el
# unico punto de entrada legitimo (Velocity) tiene que ser alcanzable desde
# internet (ADR-0005) y el free tier no incluye balanceadores de red.
#
# LA SECURITY LIST ES LA FRONTERA REAL: solo 22 (restringible), 25565/TCP y
# 19132/UDP. Los puertos del backend (8080 api-gateway, 8090 ai-orchestrator,
# 8070 world-state, 5432 postgres) NO se abren nunca. Se acceden por tunel SSH.
# ============================================================

locals {
  # Compartment por defecto = el tenancy (compartment raiz). Es lo habitual
  # en una cuenta Always Free recien creada, donde no hay compartments propios.
  compartment_id = var.compartment_ocid != "" ? var.compartment_ocid : var.tenancy_ocid
}

# ---------------- VCN ----------------

resource "oci_core_vcn" "this" {
  compartment_id = local.compartment_id
  cidr_blocks    = [var.vcn_cidr]
  display_name   = "${var.name_prefix}-vcn"

  # Habilita el resolvedor DNS interno de la VCN; necesario para poder dar
  # hostname_label a la instancia.
  dns_label = replace(var.name_prefix, "-", "")
}

# ---------------- Salida a internet ----------------

resource "oci_core_internet_gateway" "this" {
  compartment_id = local.compartment_id
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-igw"
  enabled        = true
}

resource "oci_core_route_table" "public" {
  compartment_id = local.compartment_id
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-rt-public"

  # Todo lo que no sea de la VCN sale por el internet gateway.
  route_rules {
    destination       = "0.0.0.0/0"
    destination_type  = "CIDR_BLOCK"
    network_entity_id = oci_core_internet_gateway.this.id
    description       = "Salida a internet (apt, docker hub, github, API del LLM)"
  }
}

# ---------------- Firewall a nivel de VCN ----------------

resource "oci_core_security_list" "public" {
  compartment_id = local.compartment_id
  vcn_id         = oci_core_vcn.this.id
  display_name   = "${var.name_prefix}-sl-public"

  # --- Salida: sin restricciones ---
  # La maquina necesita salir para: apt, repo de Docker, GitHub, imagenes de
  # Docker Hub, descarga de Paper/Velocity/Geyser y la API del proveedor LLM.
  egress_security_rules {
    destination      = "0.0.0.0/0"
    destination_type = "CIDR_BLOCK"
    protocol         = "all"
    description      = "Salida libre"
  }

  # --- Entrada: SSH ---
  # RESTRINGIR a la IP del dueno en cuanto la maquina este arriba.
  ingress_security_rules {
    protocol    = "6" # TCP
    source      = var.ssh_allowed_cidr
    source_type = "CIDR_BLOCK"
    description = "SSH administracion"

    tcp_options {
      min = 22
      max = 22
    }
  }

  # --- Entrada: Minecraft Java (Velocity) ---
  # Publico por definicion: es el juego. Velocity autentica contra Mojang
  # (online-mode = true) y es el unico proceso expuesto.
  ingress_security_rules {
    protocol    = "6" # TCP
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    description = "Minecraft Java - Velocity (unico punto de entrada, ADR-0005)"

    tcp_options {
      min = var.minecraft_java_port
      max = var.minecraft_java_port
    }
  }

  # --- Entrada: Minecraft Bedrock (Geyser) ---
  # Bedrock usa RakNet sobre UDP.
  ingress_security_rules {
    protocol    = "17" # UDP
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    description = "Minecraft Bedrock - Geyser (movil, consola)"

    udp_options {
      min = var.bedrock_port
      max = var.bedrock_port
    }
  }

  # --- Entrada: ICMP "fragmentation needed" (tipo 3, codigo 4) ---
  # NO es un puerto abierto: sin esto se rompe el Path MTU Discovery y algunas
  # conexiones se quedan colgadas a medias. Es la recomendacion estandar de OCI.
  ingress_security_rules {
    protocol    = "1" # ICMP
    source      = "0.0.0.0/0"
    source_type = "CIDR_BLOCK"
    description = "Path MTU Discovery (ICMP 3/4)"

    icmp_options {
      type = 3
      code = 4
    }
  }

  # NOTA DELIBERADA: no hay reglas para 8080, 8090, 8070 ni 5432.
  # Para depurar el backend desde el portatil, tunel SSH:
  #   ssh -L 8080:localhost:8080 -L 8090:localhost:8090 ubuntu@<IP>
}

# ---------------- Subred publica ----------------

resource "oci_core_subnet" "public" {
  compartment_id             = local.compartment_id
  vcn_id                     = oci_core_vcn.this.id
  cidr_block                 = var.subnet_cidr
  display_name               = "${var.name_prefix}-subnet-public"
  dns_label                  = "public"
  route_table_id             = oci_core_route_table.public.id
  security_list_ids          = [oci_core_security_list.public.id]
  prohibit_public_ip_on_vnic = false
}
