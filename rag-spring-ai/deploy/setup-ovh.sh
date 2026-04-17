#!/bin/bash
# OVH VM Setup Script
# Run this once on a fresh Ubuntu VM

set -e

echo "=== Installing Docker ==="
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER

echo "=== Creating app directory ==="
sudo mkdir -p /opt/rag-app
sudo chown $USER:$USER /opt/rag-app

echo "=== Copying compose file ==="
cp docker-compose.prod.yml /opt/rag-app/docker-compose.yml

echo "=== Setup complete ==="
echo "Log out and back in for docker group to take effect"
echo "Then run: cd /opt/rag-app && docker compose up -d"
