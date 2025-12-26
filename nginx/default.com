server {
    listen 80;
    server_name api.flunav.io;

    # 1. Allow Certbot to verify your domain (for SSL renewal)
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    # 2. Redirect everything else to HTTPS
    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl;
    server_name api.flunav.io;

    # SSL Certificates (Mounted from Host)
    ssl_certificate /etc/letsencrypt/live/api.flunav.io/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.flunav.io/privkey.pem;

    # 3. Proxy to Spring Boot
    location / {
        proxy_pass http://backend:8080; # 'backend' matches the service name in docker-compose
        
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}