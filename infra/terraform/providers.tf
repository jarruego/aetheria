# ============================================================
# Aetheria AI - Configuracion del provider OCI
# Autenticacion por API key (usuario + huella + clave privada .pem).
# Es el metodo mas simple y el unico que funciona desde una maquina que no
# esta dentro de OCI (aqui: el portatil del dueno). Ver README.md para
# generar la clave en la consola de Oracle.
# ============================================================
provider "oci" {
  tenancy_ocid = var.tenancy_ocid
  user_ocid    = var.user_ocid
  fingerprint  = var.fingerprint

  # RUTA al fichero .pem, no su contenido: asi la clave privada NUNCA
  # entra en el tfstate ni en el repositorio (.gitignore excluye *.pem).
  private_key_path = var.private_key_path

  region = var.region
}
