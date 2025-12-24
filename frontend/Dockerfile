# --- Build Stage ---
    FROM node:20-alpine AS build
    WORKDIR /app
    
    # 1. Accept Build Arguments (Passed from docker-compose)
    ARG VITE_API_BASE_URL
    # 2. Set as Environment Variable for the build process
    ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
    
    COPY package*.json ./
    
    # 3. Use 'npm ci' instead of 'install' for exact version matching (faster/safer)
    RUN npm ci
    
    COPY . .
    RUN npm run build
    
    # --- Run Stage ---
    FROM nginx:alpine
    
    # Copy built assets
    COPY --from=build /app/dist /usr/share/nginx/html
    
    # Copy custom Nginx config (Crucial for React Router)
    COPY nginx.conf /etc/nginx/conf.d/default.conf
    
    EXPOSE 80
    CMD ["nginx", "-g", "daemon off;"]