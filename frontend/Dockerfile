# ===================================================================
# Stage 1: Build the React application using pnpm
# ===================================================================
FROM node:24-alpine AS build

# Imposta la directory di lavoro all'interno del container
WORKDIR /app

ARG VITE_API_BASE_URL
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

ARG VITE_DEMO_MODE
ENV VITE_DEMO_MODE=$VITE_DEMO_MODE

ARG VITE_PUBLIC_POSTHOG_KEY
ENV VITE_PUBLIC_POSTHOG_KEY=$VITE_PUBLIC_POSTHOG_KEY
# Copia i file di definizione delle dipendenze.
# pnpm usa 'pnpm-lock.yaml' invece di 'package-lock.json'.
COPY package.json pnpm-lock.yaml ./

RUN npm install -g pnpm@10

# 2. Esegui 'pnpm install' con --frozen-lockfile.
# garantisce che vengano installate solo le versioni specificate nel lock file.
RUN pnpm install --frozen-lockfile

# Copia il resto del codice sorgente dell'applicazione
COPY . .

# Esegui lo script di build per creare i file statici di produzione
RUN pnpm run build

# ===================================================================
# Stage 2: Serve the built static files using Nginx
# ===================================================================
FROM nginx:alpine

# Copia i file di build ottimizzati dallo stage precedente ('build')
# nella cartella web di default di Nginx.
COPY --from=build /app/dist /usr/share/nginx/html

# Copia il tuo file di configurazione Nginx personalizzato.
# Questo è fondamentale per gestire il routing di una Single-Page Application.
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Esponi la porta 80, su cui Nginx è in ascolto
EXPOSE 80

# Il comando di default per avviare Nginx in primo piano (corretto per Docker)
CMD ["nginx", "-g", "daemon off;"]