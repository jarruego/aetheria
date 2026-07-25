# ============================================================
# Aetheria AI - Requisitos de version (Terraform + providers)
# Todo pinado: un `terraform init` hoy y dentro de un ano deben producir
# el mismo plan. Sin versiones fijas la reproducibilidad (regla de oro 6)
# no existe.
# ============================================================
terraform {
  required_version = ">= 1.5.0"

  required_providers {
    # Provider oficial de Oracle Cloud Infrastructure.
    oci = {
      source  = "oracle/oci"
      version = "~> 6.20"
    }
  }

  # Estado local por defecto (.gitignore ya excluye *.tfstate).
  # Si algun dia hay mas de una persona aplicando, migrar a un backend
  # remoto con bloqueo (OCI Object Storage + S3-compatible, o Terraform Cloud).
  # backend "s3" { ... }
}
