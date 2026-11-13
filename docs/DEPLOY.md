# Deploying ClinicScheduler on a single AWS EC2 instance

> **Deployment status: not deployed.** This repository's author did not create any AWS
> resources. The runbook below is reproducible and the full Compose stack was verified locally
> (`docker compose up --build`, both health endpoints UP, `scripts/smoke.sh` exit 0); treat the
> AWS steps as a tested-command runbook, not a claim of a live deployment. A real deployment
> needs HTTPS and a secrets manager before it should hold patient data.

The whole stack (MySQL, two Spring Boot services, nginx-served Angular) runs on one instance via
Docker Compose. This is a demo deployment: one host, one MySQL instance, no replication.

## 1. Sizing and image

| Choice | Value | Why |
| --- | --- | --- |
| AMI | Ubuntu Server 24.04 LTS (x86_64) | Current LTS, Docker packages available |
| Instance type | `t3.medium` (2 vCPU, 4 GB) | MySQL + two JVMs + nginx + an in-instance Maven/Node build. `t3.small` may OOM the build. |
| Storage | 30 GB gp3 | Images, Maven/npm caches, MySQL volume |
| Elastic IP | optional | Stable address across reboots |

Build the images on the instance (the runbook below) or pre-build and push them to ECR and
change `docker-compose.yml` to `image:` references; the single-instance path is simpler for a
demo.

## 2. Security group

Inbound only:

| Port | Source | Purpose |
| ---: | --- | --- |
| 22 | your admin IP /32 | SSH |
| 8080 | `0.0.0.0/0` for the demo, or your ALB/VPN CIDR | nginx SPA + `/api` proxy |

Do **not** open 3306, 8081 or 8082 to the internet. Compose publishes them on the host for local
inspection, but the security group blocks external access. For production, remove those port
mappings or bind them to `127.0.0.1` and terminate TLS at an ALB.

For HTTPS, put an Application Load Balancer (or Caddy/nginx with certificates) in front of port
8080 and forward 80/443. This runbook is HTTP-only, which is acceptable only for a demo.

## 3. Launch and connect

```bash
# From your machine, after creating the instance and key pair in the AWS console:
ssh -i /path/to/clinic-key.pem ubuntu@<EC2_PUBLIC_IP>
```

## 4. Install Docker Engine and the Compose plugin

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg git

sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null

sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER"
newgrp docker           # or log out and back in

docker --version
docker compose version
```

## 5. Clone, configure secrets, start

```bash
cd /opt 2>/dev/null || cd ~
git clone <your-repo-url> ClinicScheduler
cd ClinicScheduler

cp .env.example .env
# Generate strong values and edit .env:
openssl rand -base64 48     # use for JWT_SECRET (>= 32 bytes)
openssl rand -base64 24     # use for MYSQL_ROOT_PASSWORD
openssl rand -base64 24     # use for DB_PASSWORD

chmod 600 .env
nano .env
```

`docker-compose.yml` refuses to start when the secrets are missing (`:?` interpolation). The
demo users and JWT claims live in `AuthController`; this is not a real identity provider.

Start the stack:

```bash
docker compose up -d --build
docker compose ps
```

First start builds Maven and Node dependencies inside Docker (several minutes), creates the
MySQL volume, lets each service run its Flyway migrations, and runs the one-shot `seed`
container which loads 200 providers and 60,000 availability slots if the provider database is
empty.

## 6. Verify

```bash
# All containers (mysql, provider-service, appointment-service, web; seed exits 0)
docker compose ps

# Health endpoints
curl -s localhost:8081/actuator/health     # {"status":"UP"}
curl -s localhost:8082/actuator/health     # {"status":"UP"}
curl -s localhost:8080/healthz             # ok

# End-to-end login -> search -> book -> duplicate 409 through nginx
./scripts/smoke.sh
```

Open `http://<EC2_PUBLIC_IP>:8080` and sign in with `staff@clinic.test` / `staff123` or
`patient@clinic.test` / `patient123`.

## 7. Operations

```bash
# Follow logs
docker compose logs -f --tail=200
docker compose logs -f appointment-service provider-service

# Restart one service
docker compose restart appointment-service

# Stop without deleting data
docker compose down

# Upgrade
git pull
docker compose up -d --build

# Database backup (adjust the password source)
source .env
docker exec clinicscheduler-mysql \
  mysqldump -u"$DB_USER" -p"$DB_PASSWORD" --single-transaction --databases \
  provider_db appointment_db > "backup-$(date +%Y%m%d-%H%M).sql"
```

Make sure the backup file is copied off the instance (S3 or your workstation); it contains
appointment data.

The seed container is idempotent: it only loads data when `providers` is empty. To intentionally
reset the demo, `docker compose down -v` (deletes the MySQL volume) and start again.

## 8. Teardown

```bash
cd /path/to/ClinicScheduler
docker compose down -v          # removes containers and the MySQL volume
docker system prune -af         # optional: remove images and build cache

# Then in AWS:
#  - terminate the EC2 instance
#  - release the Elastic IP if you allocated one
#  - delete the security group and any unattached EBS volume
```

## 9. Production gaps

Before using this with real patients: replace the demo users with an OIDC/Keycloak/EHR identity
provider, terminate TLS, use AWS Secrets Manager or SSM Parameter Store instead of `.env`, put
MySQL on RDS with automated backups and Multi-AZ, remove public API port mappings, add
reservation reconciliation (see README limitations), and monitor the services with
CloudWatch/OpenTelemetry.
